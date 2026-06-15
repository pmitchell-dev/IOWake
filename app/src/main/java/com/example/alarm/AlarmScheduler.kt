package com.example.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.database.AlarmEntity
import java.util.Calendar

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: AlarmEntity) {
        if (!alarm.isEnabled) {
            cancel(alarm)
            return
        }

        val triggerTimeMs = calculateNextTriggerTime(alarm.hour, alarm.minute, alarm.getRepeatDays())
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra("ALARM_LABEL", alarm.label)
        }

        // Generate unique request code based on Alarm ID
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTimeMs, showPendingIntent)
        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d("AlarmScheduler", "Scheduled Alarm Clock ${alarm.id} at $triggerTimeMs (Enables Adaptive/Trickle Charging via OS)")
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Failed to schedule AlarmClockInfo, attempting exact fallback", e)
            // Ensure we request exact scheduling if permitted, falling back to windowed if not.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                    Log.d("AlarmScheduler", "Scheduled exact fallback alarm ${alarm.id} at $triggerTimeMs")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTimeMs,
                        pendingIntent
                    )
                    Log.d("AlarmScheduler", "Scheduled inexact fallback alarm ${alarm.id} at $triggerTimeMs")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTimeMs,
                    pendingIntent
                )
                Log.d("AlarmScheduler", "Scheduled exact fallback alarm ${alarm.id} for SDK < S at $triggerTimeMs")
            }
        }
    }

    fun cancel(alarm: AlarmEntity) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmScheduler", "Cancelled alarm ${alarm.id}")
        }
    }

    private fun calculateNextTriggerTime(hour: Int, minute: Int, repeatDays: Set<Int>): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (repeatDays.isEmpty()) {
            // One-off alarm
            if (target.before(now)) {
                // Time already passed today, set to tomorrow
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        } else {
            // Repeating alarm - find the next matching day
            var daysOffset = 0
            val todayDayOfWeek = now.get(Calendar.DAY_OF_WEEK) // 1 = Sunday, ..., 7 = Saturday

            // Check if today matches, and the target time is in the future
            if (repeatDays.contains(todayDayOfWeek) && target.after(now)) {
                return target.timeInMillis
            }

            // Otherwise, scan up to 7 days ahead to find the next active weekday
            for (i in 1..7) {
                val checkDayOfWeek = ((todayDayOfWeek - 1 + i) % 7) + 1
                if (repeatDays.contains(checkDayOfWeek)) {
                    daysOffset = i
                    break
                }
            }

            if (daysOffset == 0) {
                // Fallback (shouldn't happen because repeatDays is non-empty)
                daysOffset = 1
            }

            target.add(Calendar.DAY_OF_YEAR, daysOffset)
            return target.timeInMillis
        }
    }
}
