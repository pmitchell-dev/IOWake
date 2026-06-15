package com.example.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.database.AlarmDatabase
import com.example.data.database.AlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.Calendar

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var volumeRampJob: Job? = null
    private var vibrationJob: Job? = null
    private var synthSoundJob: Job? = null
    @Volatile
    private var currentVolumeValue = 1.0f
    @Volatile
    private var isDucked = false

    companion object {
        const val ACTION_START = "com.example.alarm.ACTION_START"
        const val ACTION_STOP = "com.example.alarm.ACTION_STOP"
        const val ACTION_SNOOZE = "com.example.alarm.ACTION_SNOOZE"
        const val ACTION_DUCK = "com.example.alarm.ACTION_DUCK"
        const val ACTION_UNDUCK = "com.example.alarm.ACTION_UNDUCK"
        const val CHANNEL_ID = "alarm_service_channel"
        const val NOTIFICATION_ID = 2605
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeVibrator()
    }

    private fun initializeVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val alarmId = intent?.getIntExtra("ALARM_ID", -1) ?: -1
        val label = intent?.getStringExtra("ALARM_LABEL") ?: "Wake Up!"

        Log.d("AlarmService", "onStartCommand execution. Action: $action, AlarmId: $alarmId")

        when (action) {
            ACTION_START -> {
                if (alarmId != -1) {
                    startRingingAlarm(alarmId, label)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                handleStopAlarm()
            }
            ACTION_SNOOZE -> {
                if (alarmId != -1) {
                    handleSnoozeAlarm(alarmId)
                } else {
                    handleStopAlarm()
                }
            }
            ACTION_DUCK -> {
                handleDuckVolume(true)
            }
            ACTION_UNDUCK -> {
                handleDuckVolume(false)
            }
        }
        return START_STICKY
    }

    private fun handleDuckVolume(duck: Boolean) {
        isDucked = duck
        val factor = if (duck) 0.15f else 1.0f
        val targetVolume = currentVolumeValue * factor
        mediaPlayer?.let { player ->
            try {
                player.setVolume(targetVolume, targetVolume)
            } catch (e: Exception) {
                Log.e("AlarmService", "Failed to set player volume on duck status", e)
            }
        }
        Log.d("AlarmService", "Alarm volume duck status changed to: $isDucked (target volume: $targetVolume)")
    }

    private fun startRingingAlarm(alarmId: Int, label: String) {
        // Start foreground notification immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildRingingNotification(alarmId, label),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildRingingNotification(alarmId, label))
        }

        serviceScope.launch {
            val db = AlarmDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(alarmId)
            if (alarm == null) {
                Log.e("AlarmService", "Failed to retrieve alarm with id $alarmId")
                stopSelf()
                return@launch
            }

            // Expose alarm to UI
            AlarmStateHolder.activeRingingAlarm.value = alarm

            // Initialize and play media
            playAlarmSound(alarm)

            // Start physical device vibration
            startContinuousVibration()
        }
    }

    private fun playAlarmSound(alarm: AlarmEntity) {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            synthSoundJob?.cancel()
            synthSoundJob = null

            // Initial volume value
            currentVolumeValue = if (alarm.volumeRamping) 0.1f else 1.0f

            if (alarm.soundUri.startsWith("synth_")) {
                playSynthSound(alarm.soundUri)
                if (alarm.volumeRamping) {
                    startVolumeRamping(isSynth = true)
                }
            } else {
                mediaPlayer = MediaPlayer()
                
                val uri: Uri = when (alarm.soundUri) {
                    "system_alarm" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    "system_ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    "system_notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    else -> try {
                        Uri.parse(alarm.soundUri)
                    } catch (e: Exception) {
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    }
                } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                  ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                  ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                mediaPlayer?.setDataSource(applicationContext, uri)

                // Routing audio to physical alarm stream
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                mediaPlayer?.setAudioAttributes(audioAttributes)
                mediaPlayer?.isLooping = true
                mediaPlayer?.prepare()

                mediaPlayer?.setVolume(currentVolumeValue, currentVolumeValue)
                if (alarm.volumeRamping) {
                    startVolumeRamping(isSynth = false)
                }

                mediaPlayer?.start()
                Log.d("AlarmService", "Started playing audio stream...")
            }
        } catch (e: Exception) {
            Log.e("AlarmService", "Failed to prepare or start media player", e)
        }
    }

    private fun startVolumeRamping(isSynth: Boolean) {
        volumeRampJob?.cancel()
        volumeRampJob = serviceScope.launch {
            var volume = 0.1f
            while (volume < 1.0f) {
                delay(5000) // Increase volume every 5 seconds
                volume += 0.15f
                if (volume > 1.0f) volume = 1.0f
                currentVolumeValue = volume
                if (!isSynth) {
                    val factor = if (isDucked) 0.15f else 1.0f
                    val targetVol = volume * factor
                    mediaPlayer?.setVolume(targetVol, targetVol)
                }
                Log.d("AlarmService", "Ramping alarm volume to ${volume * 100}%")
            }
        }
    }

    private fun playSynthSound(type: String) {
        synthSoundJob?.cancel()
        synthSoundJob = serviceScope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val numSamples = 22050 // 0.5 sec blocks
            val buffer = ShortArray(numSamples)
            
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioTrack = AudioTrack(
                AudioManager.STREAM_ALARM,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize.coerceAtLeast(numSamples * 2),
                AudioTrack.MODE_STREAM
            )
            
            try {
                audioTrack.play()
                var phase = 0.0
                
                while (isActive) {
                    val currentVolume = if (isDucked) currentVolumeValue * 0.15f else currentVolumeValue
                    
                    when (type) {
                        "synth_digital_beep" -> {
                            for (bIdx in 0 until 4) {
                                if (!isActive) break
                                val beepLen = (sampleRate * 0.15).toInt()
                                val pauseLen = (sampleRate * 0.1).toInt()
                                
                                var k = 0
                                while (k < beepLen && isActive) {
                                    val chunk = minOf(numSamples, beepLen - k)
                                    for (j in 0 until chunk) {
                                        buffer[j] = (Math.sin(phase) * 32767 * currentVolume).toInt().toShort()
                                        phase += 2 * Math.PI * 1200.0 / sampleRate
                                    }
                                    audioTrack.write(buffer, 0, chunk)
                                    k += chunk
                                }
                                
                                var s = 0
                                while (s < pauseLen && isActive) {
                                    val chunk = minOf(numSamples, pauseLen - s)
                                    for (j in 0 until chunk) buffer[j] = 0
                                    audioTrack.write(buffer, 0, chunk)
                                    s += chunk
                                }
                            }
                            val longPause = (sampleRate * 0.5).toInt()
                            var s = 0
                            while (s < longPause && isActive) {
                                val chunk = minOf(numSamples, longPause - s)
                                for (j in 0 until chunk) buffer[j] = 0
                                audioTrack.write(buffer, 0, chunk)
                                s += chunk
                            }
                        }
                        "synth_gentle_chimes" -> {
                            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
                            for (note in notes) {
                                if (!isActive) break
                                val noteLen = (sampleRate * 0.3).toInt()
                                var k = 0
                                var notePhase = 0.0
                                while (k < noteLen && isActive) {
                                    val chunk = minOf(numSamples, noteLen - k)
                                    for (j in 0 until chunk) {
                                        val progress = (k + j).toDouble() / noteLen
                                        val env = Math.exp(-4.0 * progress)
                                        buffer[j] = (Math.sin(notePhase) * 32767 * currentVolume * env).toInt().toShort()
                                        notePhase += 2 * Math.PI * note / sampleRate
                                    }
                                    audioTrack.write(buffer, 0, chunk)
                                    k += chunk
                                }
                            }
                            val chimePause = (sampleRate * 0.8).toInt()
                            var s = 0
                            while (s < chimePause && isActive) {
                                val chunk = minOf(numSamples, chimePause - s)
                                for (j in 0 until chunk) buffer[j] = 0
                                audioTrack.write(buffer, 0, chunk)
                                s += chunk
                            }
                        }
                        "synth_laser_siren" -> {
                            val duration = (sampleRate * 1.0).toInt()
                            var k = 0
                            while (k < duration && isActive) {
                                val chunk = minOf(numSamples, duration - k)
                                for (j in 0 until chunk) {
                                    val progress = (k + j).toDouble() / duration
                                    val sweep = 600.0 + 1000.0 * Math.abs(Math.sin(3.0 * Math.PI * progress))
                                    buffer[j] = (Math.sin(phase) * 28000 * currentVolume).toInt().toShort()
                                    phase += 2 * Math.PI * sweep / sampleRate
                                }
                                audioTrack.write(buffer, 0, chunk)
                                k += chunk
                            }
                        }
                        else -> {
                            val freq = 440.0
                            for (j in 0 until numSamples) {
                                buffer[j] = (Math.sin(phase) * 32767 * currentVolume).toInt().toShort()
                                phase += 2 * Math.PI * freq / sampleRate
                            }
                            audioTrack.write(buffer, 0, numSamples)
                        }
                    }
                }
                
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {}
            } catch (e: Exception) {
                Log.e("AlarmServiceSynth", "Error during synthesized playback", e)
            }
        }
    }

    private fun startContinuousVibration() {
        vibrationJob?.cancel()
        vibrationJob = serviceScope.launch {
            while (true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(1000)
                }
                delay(2000) // Pause for 1 second between vibrations
            }
        }
    }

    private fun handleStopAlarm() {
        Log.d("AlarmService", "Stopping Alarm ringing...")
        releaseResources()

        serviceScope.launch {
            val activeAlarm = AlarmStateHolder.activeRingingAlarm.value
            if (activeAlarm != null) {
                val db = AlarmDatabase.getDatabase(applicationContext)
                // Clear the temporary snoozedCount since we successfully dismissed it
                val updatedAlarm = activeAlarm.copy(snoozedCount = 0, isEnabled = if (activeAlarm.repeatDaysCode.isBlank()) false else activeAlarm.isEnabled)
                db.alarmDao().updateAlarm(updatedAlarm)

                // Reschedule for next occurrence if repeating, otherwise scheduler will disable
                AlarmScheduler(applicationContext).schedule(updatedAlarm)
            }

            AlarmStateHolder.activeRingingAlarm.value = null
            stopForeground(true)
            stopSelf()
        }
    }

    private fun handleSnoozeAlarm(alarmId: Int) {
        Log.d("AlarmService", "Snoozing Alarm ID $alarmId")
        releaseResources()

        serviceScope.launch {
            val db = AlarmDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(alarmId)

            if (alarm != null) {
                // Safeguard snooze checks
                val isSnoozeAllowed = alarm.snoozeModeCode == "PENALTY" && alarm.snoozedCount < alarm.maxSnoozes
                if (isSnoozeAllowed) {
                    val newSnoozedCount = alarm.snoozedCount + 1
                    
                    // accountability penalty: shrink interval as snoozes progress:
                    // e.g., 10 minutes -> 5 minutes -> LOCKDOWN
                    val divider = if (newSnoozedCount > 0) newSnoozedCount + 1 else 1
                    val calculatedMinutes = (alarm.snoozeIntervalMinutes / divider).coerceAtLeast(1)

                    val updatedAlarm = alarm.copy(snoozedCount = newSnoozedCount)
                    db.alarmDao().updateAlarm(updatedAlarm)

                    // Schedule a special high-priority snooze callback
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val snoozeCalendar = Calendar.getInstance().apply {
                        add(Calendar.MINUTE, calculatedMinutes)
                    }

                    val intent = Intent(applicationContext, AlarmReceiver::class.java).apply {
                        putExtra("ALARM_ID", alarm.id)
                        putExtra("ALARM_LABEL", "${alarm.label} (Snooze $newSnoozedCount)")
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        applicationContext,
                        alarm.id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeCalendar.timeInMillis, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeCalendar.timeInMillis, pendingIntent)
                    }

                    Log.d("AlarmService", "Snoozed successfully. Ring again in $calculatedMinutes min (Snooze count: $newSnoozedCount)")
                } else {
                    Log.d("AlarmService", "Snooze rejected. Either snooze disabled or maximum snoozes exceeded.")
                }
            }

            AlarmStateHolder.activeRingingAlarm.value = null
            stopForeground(true)
            stopSelf()
        }
    }

    private fun releaseResources() {
        volumeRampJob?.cancel()
        vibrationJob?.cancel()
        synthSoundJob?.cancel()
        synthSoundJob = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("AlarmService", "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
        vibrator?.cancel()
    }

    override fun onDestroy() {
        releaseResources()
        super.onDestroy()
    }

    private fun buildRingingNotification(alarmId: Int, label: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("RINGING_ALARM_ID", alarmId)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            alarmId,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Math Alarm Clock is Ringing!")
            .setContentText(label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Alarm Ringing Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority channel for playing math mission alarm ringers."
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
