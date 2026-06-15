package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alarm.AlarmService
import com.example.alarm.AlarmStateHolder
import com.example.alarm.MemoryPuzzleEngine
import com.example.alarm.SequencePuzzleEngine
import com.example.alarm.MemoryCard
import com.example.alarm.MathPuzzle
import com.example.alarm.MathPuzzleEngine
import com.example.data.database.AlarmDatabase
import com.example.data.database.AlarmEntity
import com.example.data.repository.AlarmRepository
import com.example.ui.theme.*
import com.example.ui.theme.Typography
import com.example.ui.viewmodel.AlarmViewModel
import com.example.ui.viewmodel.AlarmViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

val MathPuzzleSaver = Saver<MathPuzzle, List<Any>>(
    save = { listOf(it.question, it.answer) },
    restore = { MathPuzzle(question = it[0] as String, answer = it[1] as Int) }
)

val MemoryCardSaver = Saver<MemoryCard, List<Any>>(
    save = { listOf(it.id, it.symbol, it.isFlipped, it.isMatched) },
    restore = { MemoryCard(id = it[0] as Int, symbol = it[1] as String, isFlipped = it[2] as Boolean, isMatched = it[3] as Boolean) }
)

val MemoryCardListSaver = Saver<List<MemoryCard>, List<Any>>(
    save = { list -> list.flatMap { listOf(it.id, it.symbol, it.isFlipped, it.isMatched) } },
    restore = { list ->
        list.chunked(4).map {
            MemoryCard(
                id = it[0] as Int,
                symbol = it[1] as String,
                isFlipped = it[2] as Boolean,
                isMatched = it[3] as Boolean
            )
        }
    }
)

val IntListSaver = Saver<List<Int>, IntArray>(
    save = { it.toIntArray() },
    restore = { it.toList() }
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AlarmDatabase.getDatabase(applicationContext)
        val repository = AlarmRepository(database.alarmDao(), applicationContext)
        val factory = AlarmViewModelFactory(repository)

        setContent {
            MyApplicationTheme {
                MainAppContainer(factory = factory)
            }
        }
    }
}

// Live ticking clock flow for high fidelity UI
val systemTimeFlow = flow {
    while (true) {
        val cal = Calendar.getInstance()
        val timeString = SimpleDateFormat("hh:mm", Locale.getDefault()).format(cal.time)
        val amPmString = SimpleDateFormat("a", Locale.getDefault()).format(cal.time).uppercase()
        val dateString = SimpleDateFormat("EEEE, MMMM dd", Locale.getDefault()).format(cal.time)
        emit(Triple(timeString, amPmString, dateString))
        delay(1000)
    }
}

@Composable
fun MainAppContainer(factory: AlarmViewModelFactory) {
    val context = LocalContext.current
    val viewModel: AlarmViewModel = viewModel(factory = factory)
    
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val editingAlarm by viewModel.editingAlarm.collectAsStateWithLifecycle()
    val ringingAlarm by AlarmStateHolder.activeRingingAlarm.collectAsStateWithLifecycle()
    
    var isPermissionAllowed by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    
    val reqPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { allowed ->
        isPermissionAllowed = allowed
    }

    LaunchedEffect(Unit) {
        if (!isPermissionAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reqPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var practiceDifficulty by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = DeepSlateBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                ringingAlarm != null -> {
                    ActiveAlarmOverlay(
                        alarm = ringingAlarm!!,
                        onSolved = {
                            val intent = Intent(context, AlarmService::class.java).apply {
                                action = AlarmService.ACTION_STOP
                            }
                            context.startService(intent)
                            Toast.makeText(context, "Sequence solved! Waking state verified.", Toast.LENGTH_LONG).show()
                        },
                        onSnoozed = {
                            val intent = Intent(context, AlarmService::class.java).apply {
                                action = AlarmService.ACTION_SNOOZE
                                putExtra("ALARM_ID", ringingAlarm!!.id)
                            }
                            context.startService(intent)
                            Toast.makeText(context, "Alarm snoozed. Interval shortened.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                practiceDifficulty != null -> {
                    PracticeOverlay(
                        difficulty = practiceDifficulty!!,
                        onDismiss = { practiceDifficulty = null }
                    )
                }
                editingAlarm != null -> {
                    AlarmEditPanel(
                        alarm = editingAlarm!!,
                        onSave = { updated -> viewModel.saveAlarm(updated) },
                        onDismiss = { viewModel.setEditingAlarm(null) }
                    )
                }
                else -> {
                    AlarmsDashboard(
                        alarms = alarms,
                        onToggleAlarm = { alarm, enabled -> viewModel.toggleAlarm(alarm, enabled) },
                        onDeleteAlarm = { alarm -> viewModel.deleteAlarm(alarm) },
                        onEditAlarm = { alarm -> viewModel.setEditingAlarm(alarm) },
                        onAddAlarm = {
                            val cal = Calendar.getInstance()
                            viewModel.setEditingAlarm(
                                AlarmEntity(
                                    hour = cal.get(Calendar.HOUR_OF_DAY),
                                    minute = cal.get(Calendar.MINUTE),
                                    isEnabled = true
                                )
                            )
                        },
                        onPracticeTrigger = { difficulty -> practiceDifficulty = difficulty }
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmsDashboard(
    alarms: List<AlarmEntity>,
    onToggleAlarm: (AlarmEntity, Boolean) -> Unit,
    onDeleteAlarm: (AlarmEntity) -> Unit,
    onEditAlarm: (AlarmEntity) -> Unit,
    onAddAlarm: () -> Unit,
    onPracticeTrigger: (String) -> Unit
) {
    val liveClockState by systemTimeFlow.collectAsState(
        initial = Triple("--:--", "AM", "Today")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper: Clock card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = liveClockState.third,
                    color = ElectricTeal,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = liveClockState.first,
                        color = OffWhite,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = (-1).sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = liveClockState.second,
                        color = CosmicBlueLight,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                val activeCount = alarms.count { it.isEnabled }
                Text(
                    text = if (activeCount > 0) "$activeCount alarm(s) armed & ready" else "Accountability offline • 0 active",
                    color = if (activeCount > 0) SuccessGreen else GhostText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Sandbox practice row
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Brain Check Practice", color = OffWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Test the math lock logic anytime", color = GhostText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { onPracticeTrigger("EASY") },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CosmicBlue.copy(alpha = 0.2f), contentColor = CosmicBlueLight),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                    ) {
                        Text("Easy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { onPracticeTrigger("MEDIUM") },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = AmberRamping.copy(alpha = 0.2f), contentColor = AmberRamping),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                    ) {
                        Text("Med", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { onPracticeTrigger("HARD") },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = ErrorRed.copy(alpha = 0.2f), contentColor = ErrorRed),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                    ) {
                        Text("Hard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Scrollable alarms column
        Box(modifier = Modifier.weight(1f)) {
            if (alarms.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "No alarms",
                        tint = GhostText,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your morning discipline starts here",
                        color = OffWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Press the + button to build a mathematical alarm safeguard",
                        color = GhostText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmItemCard(
                            alarm = alarm,
                            onToggle = { enabled -> onToggleAlarm(alarm, enabled) },
                            onDelete = { onDeleteAlarm(alarm) },
                            onClick = { onEditAlarm(alarm) }
                        )
                    }
                }
            }

            LargeFloatingActionButton(
                onClick = onAddAlarm,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("add_alarm_button"),
                containerColor = CosmicBlue,
                contentColor = OffWhite,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Alarm",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

@Composable
fun AlarmItemCard(
    alarm: AlarmEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val displayHour = if (alarm.hour % 12 == 0) 12 else alarm.hour % 12
    val displayMinute = String.format(Locale.getDefault(), "%02d", alarm.minute)
    val amPm = if (alarm.hour >= 12) "PM" else "AM"
    val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val repeatSet = alarm.getRepeatDays()
    val cardAlpha = if (alarm.isEnabled) 1.0f else 0.5f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .testTag("alarm_card_${alarm.id}"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface.copy(alpha = cardAlpha)),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface.copy(alpha = cardAlpha * 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$displayHour:$displayMinute",
                            color = if (alarm.isEnabled) OffWhite else GhostText,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = amPm,
                            color = if (alarm.isEnabled) CosmicBlueLight else GhostText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    if (alarm.label.isNotBlank()) {
                        Text(
                            text = alarm.label,
                            color = if (alarm.isEnabled) OffWhite.copy(alpha = 0.8f) else GhostText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OffWhite,
                        checkedTrackColor = CosmicBlue,
                        uncheckedThumbColor = GhostText,
                        uncheckedTrackColor = LightSlateSurface
                    ),
                    modifier = Modifier.testTag("alarm_switch_${alarm.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (repeatSet.isEmpty()) {
                    Text(
                        text = "Once",
                        color = ElectricTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 1..7) {
                            val active = repeatSet.contains(i)
                            val letter = when (i) {
                                1 -> "S"
                                2 -> "M"
                                3 -> "T"
                                4 -> "W"
                                5 -> "T"
                                6 -> "F"
                                7 -> "S"
                                else -> ""
                            }
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active && alarm.isEnabled) CosmicBlue
                                        else if (active) LightSlateSurface
                                        else Color.Transparent
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = letter,
                                    color = if (active) OffWhite else GhostText.copy(alpha = 0.5f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val puzzleBadgeColor = when (alarm.puzzleType.uppercase()) {
                        "MEMORY" -> ElectricTeal
                        "SEQUENCE" -> CosmicBlue
                        else -> CosmicBlueLight
                    }
                    Surface(
                        color = puzzleBadgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = alarm.puzzleType,
                            color = puzzleBadgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    val badgeColor = when (alarm.difficulty.uppercase()) {
                        "EASY" -> SuccessGreen
                        "HARD" -> ErrorRed
                        else -> AmberRamping
                    }
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${alarm.difficulty} × ${alarm.questionCount}",
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (alarm.snoozeModeCode == "NONE") {
                        Surface(
                            color = ErrorRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "LOCKED",
                                color = ErrorRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (alarm.snoozedCount > 0) {
                        Surface(
                            color = AmberRamping.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Snooze ${alarm.snoozedCount}/${alarm.maxSnoozes}",
                                color = AmberRamping,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_alarm_${alarm.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Alarm",
                            tint = ErrorRed.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmEditPanel(
    alarm: AlarmEntity,
    onSave: (AlarmEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(alarm.label) }
    var selectedHour by remember { mutableStateOf(if (alarm.hour % 12 == 0) 12 else alarm.hour % 12) }
    var selectedMinute by remember { mutableStateOf(alarm.minute) }
    var amPmState by remember { mutableStateOf(if (alarm.hour >= 12) "PM" else "AM") }
    
    val repeatDays = remember { mutableStateOf(alarm.getRepeatDays()) }
    var difficulty by remember { mutableStateOf(alarm.difficulty) }
    var easyJustAddition by remember { mutableStateOf(alarm.easyJustAddition) }
    var questionCount by remember { mutableFloatStateOf(alarm.questionCount.toFloat()) }
    var snoozeMode by remember { mutableStateOf(alarm.snoozeModeCode) }
    var maxSnoozes by remember { mutableFloatStateOf(alarm.maxSnoozes.toFloat()) }
    var snoozeInterval by remember { mutableFloatStateOf(alarm.snoozeIntervalMinutes.toFloat()) }
    var volumeRamping by remember { mutableStateOf(alarm.volumeRamping) }
    var puzzleType by remember { mutableStateOf(alarm.puzzleType) }
    var soundName by remember { mutableStateOf(alarm.soundName) }
    var soundUri by remember { mutableStateOf(alarm.soundUri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSlateBg)
            .padding(16.dp)
            .testTag("alarm_edit_panel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_edit_panel")) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = OffWhite)
            }
            Text(
                text = if (alarm.id == 0) "Configure Alarm" else "Edit Safeguard",
                color = OffWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = {
                    var finalHour = selectedHour
                    if (amPmState == "PM" && selectedHour < 12) {
                        finalHour += 12
                    } else if (amPmState == "AM" && selectedHour == 12) {
                        finalHour = 0
                    }

                    val code = repeatDays.value.sorted().joinToString(",")
                    val updated = alarm.copy(
                        label = label,
                        hour = finalHour,
                        minute = selectedMinute,
                        repeatDaysCode = code,
                        difficulty = difficulty,
                        questionCount = questionCount.toInt(),
                        snoozeModeCode = snoozeMode,
                        maxSnoozes = maxSnoozes.toInt(),
                        snoozeIntervalMinutes = snoozeInterval.toInt(),
                        volumeRamping = volumeRamping,
                        puzzleType = puzzleType,
                        easyJustAddition = if (difficulty.uppercase() == "EASY" && puzzleType.uppercase() == "MATH") easyJustAddition else false,
                        soundName = soundName,
                        soundUri = soundUri,
                        isEnabled = true
                    )
                    onSave(updated)
                },
                modifier = Modifier.testTag("save_alarm_button")
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "Save", tint = SuccessGreen)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Elegant time picker dials
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Target Wakeup Time", color = GhostText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = {
                                    selectedHour = if (selectedHour >= 12) 1 else selectedHour + 1
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, null, tint = CosmicBlueLight)
                                }
                                Surface(
                                    color = LightSlateSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(64.dp, 64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = String.format(Locale.getDefault(), "%02d", selectedHour),
                                            color = OffWhite,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    selectedHour = if (selectedHour <= 1) 12 else selectedHour - 1
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, null, tint = CosmicBlueLight)
                                }
                            }

                            Text(
                                text = ":",
                                color = OffWhite,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = {
                                    selectedMinute = if (selectedMinute >= 59) 0 else selectedMinute + 1
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, null, tint = CosmicBlueLight)
                                }
                                Surface(
                                    color = LightSlateSurface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(64.dp, 64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = String.format(Locale.getDefault(), "%02d", selectedMinute),
                                            color = OffWhite,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    selectedMinute = if (selectedMinute <= 0) 59 else selectedMinute - 1
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, null, tint = CosmicBlueLight)
                                }
                            }

                            Spacer(modifier = Modifier.width(20.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { amPmState = "AM" },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (amPmState == "AM") CosmicBlue else LightSlateSurface,
                                        contentColor = OffWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 1.dp, minWidth = 1.dp)
                                ) {
                                    Text("AM", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { amPmState = "PM" },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (amPmState == "PM") CosmicBlue else LightSlateSurface,
                                        contentColor = OffWhite
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 1.dp, minWidth = 1.dp)
                                ) {
                                    Text("PM", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Description Label Textbox
            item {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Alarm Label (e.g. Work Morning)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OffWhite,
                        unfocusedTextColor = OffWhite,
                        focusedBorderColor = CosmicBlue,
                        unfocusedBorderColor = LightSlateSurface,
                        focusedLabelColor = ElectricTeal,
                        unfocusedLabelColor = GhostText
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("edit_alarm_label_input")
                )
            }

            // Custom Weekday Repeat chips
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text("Repeat Alarm Schedule", color = GhostText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val daysOfWeek = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                            for (i in 1..7) {
                                val active = repeatDays.value.contains(i)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (active) CosmicBlue else LightSlateSurface)
                                        .clickable {
                                            val current = repeatDays.value.toMutableSet()
                                            if (current.contains(i)) {
                                                current.remove(i)
                                            } else {
                                                current.add(i)
                                            }
                                            repeatDays.value = current
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = daysOfWeek[i - 1].take(1),
                                        color = if (active) OffWhite else GhostText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Puzzle Type Selector Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text("Lockdown Safeguard Method", color = GhostText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("MATH", "MEMORY", "SEQUENCE").forEach { type ->
                                val active = puzzleType.uppercase() == type
                                
                                val activeBg = when (type) {
                                    "MATH" -> CosmicBlue
                                    "MEMORY" -> ElectricTeal
                                    else -> AmberRamping
                                }
                                
                                val icon = when (type) {
                                    "MATH" -> Icons.Default.Check
                                    "MEMORY" -> Icons.Default.Favorite
                                    else -> Icons.Default.PlayArrow
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) activeBg else LightSlateSurface)
                                        .clickable { puzzleType = type }
                                        .padding(vertical = 10.dp)
                                        .testTag("puzzle_type_tab_$type"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (active) VoidBlack else GhostText,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = when (type) {
                                                "MATH" -> "Math"
                                                "MEMORY" -> "Memory"
                                                "SEQUENCE" -> "Sequence"
                                                else -> type
                                            },
                                            color = if (active) VoidBlack else GhostText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Cognitive Lock math parameters
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        val cardTitle = when (puzzleType) {
                            "MEMORY" -> "Cognitive Memory Lock"
                            "SEQUENCE" -> "Cognitive Sequence Lock"
                            else -> "Cognitive Math Lock"
                        }
                        Text(cardTitle, color = GhostText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Puzzle Difficulty", color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("EASY", "MEDIUM", "HARD").forEach { level ->
                                val active = difficulty.uppercase() == level
                                val activeColor = when (level) {
                                    "EASY" -> SuccessGreen
                                    "HARD" -> ErrorRed
                                    else -> AmberRamping
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) activeColor else LightSlateSurface)
                                        .clickable { difficulty = level }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = level,
                                        color = if (active) OffWhite else GhostText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (difficulty.uppercase() == "EASY" && puzzleType.uppercase() == "MATH") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LightSlateSurface)
                                    .clickable { easyJustAddition = !easyJustAddition }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text("Just Addition", color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Disable subtraction for Easy mode", color = GhostText, fontSize = 10.sp)
                                    }
                                }
                                Switch(
                                    checked = easyJustAddition,
                                    onCheckedChange = { easyJustAddition = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SuccessGreen,
                                        checkedTrackColor = SuccessGreen.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.testTag("easy_just_addition_switch")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val sliderLabel = when (puzzleType) {
                            "MEMORY" -> "Consecutive Memory Boards Required"
                            "SEQUENCE" -> "Consecutive Patterns Required"
                            else -> "Consecutive Math Answers Required"
                        }
                        val countLabel = when (puzzleType) {
                            "MEMORY" -> "${questionCount.toInt()} Board(s)"
                            "SEQUENCE" -> "${questionCount.toInt()} Pattern(s)"
                            else -> "${questionCount.toInt()} Question(s)"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(sliderLabel, color = OffWhite, fontSize = 13.sp)
                            Text(countLabel, color = ElectricTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = questionCount,
                            onValueChange = { questionCount = it },
                            valueRange = 1f..5f,
                            steps = 3,
                            colors = SliderDefaults.colors(
                                thumbColor = ElectricTeal,
                                activeTrackColor = ElectricTeal,
                                inactiveTrackColor = LightSlateSurface
                            ),
                            modifier = Modifier.testTag("question_count_slider")
                        )
                    }
                }
            }

            // Anti-Snooze controls
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text("Accountability Anti-Snooze", color = GhostText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Active Snooze Protection", color = OffWhite, fontSize = 13.sp)
                            Row {
                                AssistChip(
                                    onClick = { snoozeMode = "NONE" },
                                    label = { Text("No Snooze") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (snoozeMode == "NONE") ErrorRed else LightSlateSurface,
                                        labelColor = if (snoozeMode == "NONE") OffWhite else GhostText
                                    ),
                                    border = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                AssistChip(
                                    onClick = { snoozeMode = "PENALTY" },
                                    label = { Text("Penalty Mode") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (snoozeMode == "PENALTY") CosmicBlue else LightSlateSurface,
                                        labelColor = if (snoozeMode == "PENALTY") OffWhite else GhostText
                                    ),
                                    border = null
                                )
                            }
                        }

                        if (snoozeMode == "PENALTY") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Maximum Allowed Snoozes", color = OffWhite, fontSize = 13.sp)
                                Text("${maxSnoozes.toInt()} Time(s)", color = AmberRamping, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = maxSnoozes,
                                onValueChange = { maxSnoozes = it },
                                valueRange = 1f..3f,
                                steps = 1,
                                colors = SliderDefaults.colors(thumbColor = AmberRamping, activeTrackColor = AmberRamping)
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Initial Snooze Interval", color = OffWhite, fontSize = 13.sp)
                                Text("${snoozeInterval.toInt()} Min", color = AmberRamping, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = snoozeInterval,
                                onValueChange = { snoozeInterval = it },
                                valueRange = 5f..15f,
                                steps = 1,
                                colors = SliderDefaults.colors(thumbColor = AmberRamping, activeTrackColor = AmberRamping)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Warning: Turning off snooze forces you to solve math immediately to silent the alarm.",
                                color = ErrorRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Volume Escalate slider
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Progressive Volume Ramping", color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Gradually escalates from 10% to 100% to prevent morning shock.", color = GhostText, fontSize = 11.sp)
                        }
                        Switch(
                            checked = volumeRamping,
                            onCheckedChange = { volumeRamping = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = CosmicBlue, checkedThumbColor = OffWhite)
                        )
                    }
                }
            }

            // Alarm Sound Selector
            item {
                var showSoundDialog by remember { mutableStateOf(false) }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSoundDialog = true }
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = ElectricTeal,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text("Alarm Sound", color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(soundName, color = GhostText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Change alarm sound",
                                tint = GhostText,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (showSoundDialog) {
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    
                    // Available sounds list
                    val availableSounds = remember {
                        val list = mutableListOf(
                            "Classic Digital Beep" to "synth_digital_beep",
                            "Gentle Chimes Ascent" to "synth_gentle_chimes",
                            "Urgent Laser Siren" to "synth_laser_siren",
                            "System Alarm Alert" to "system_alarm",
                            "System Phone Ringtone" to "system_ringtone",
                            "System Notification" to "system_notification"
                        )
                        try {
                            val rm = RingtoneManager(context)
                            rm.setType(RingtoneManager.TYPE_ALARM)
                            val cursor = rm.cursor
                            if (cursor != null) {
                                while (cursor.moveToNext()) {
                                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                                    val uri = rm.getRingtoneUri(cursor.position).toString()
                                    list.add(title to uri)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("RingtoneQueryInUI", "Failed to query system alarms", e)
                        }
                        list.distinctBy { it.second }
                    }

                    // Local sound preview states
                    var currentlyPlayingUri by remember { mutableStateOf<String?>(null) }
                    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
                    var previewJob by remember { mutableStateOf<Job?>(null) }

                    fun stopPreview() {
                        previewJob?.cancel()
                        previewJob = null
                        try {
                            previewPlayer?.stop()
                            previewPlayer?.release()
                        } catch (e: Exception) {}
                        previewPlayer = null
                        currentlyPlayingUri = null
                    }

                    fun playPreview(uriString: String) {
                        stopPreview()
                        currentlyPlayingUri = uriString
                        
                        if (uriString.startsWith("synth_")) {
                            previewJob = scope.launch(Dispatchers.Default) {
                                val sampleRate = 44100
                                val numSamples = 22050
                                val buffer = ShortArray(numSamples)
                                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                                val tr = AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    sampleRate,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    minBuf.coerceAtLeast(numSamples * 2),
                                    AudioTrack.MODE_STREAM
                                )
                                try {
                                    tr.play()
                                    var phase = 0.0
                                    val startTime = System.currentTimeMillis()
                                    while (isActive && System.currentTimeMillis() - startTime < 4000) {
                                        when (uriString) {
                                            "synth_digital_beep" -> {
                                                val freq = 1200.0
                                                for (bIdx in 0 until 4) {
                                                    if (!isActive) break
                                                    val beepLen = (sampleRate * 0.15).toInt()
                                                    val pauseLen = (sampleRate * 0.1).toInt()
                                                    
                                                    var k = 0
                                                    while (k < beepLen && isActive) {
                                                        val chunk = minOf(numSamples, beepLen - k)
                                                        for (j in 0 until chunk) {
                                                            buffer[j] = (Math.sin(phase) * 18000).toInt().toShort()
                                                            phase += 2 * Math.PI * freq / sampleRate
                                                        }
                                                        tr.write(buffer, 0, chunk)
                                                        k += chunk
                                                    }
                                                    
                                                    var s = 0
                                                    while (s < pauseLen && isActive) {
                                                        val chunk = minOf(numSamples, pauseLen - s)
                                                        for (j in 0 until chunk) buffer[j] = 0
                                                        tr.write(buffer, 0, chunk)
                                                        s += chunk
                                                    }
                                                }
                                                val longPause = (sampleRate * 0.5).toInt()
                                                var s = 0
                                                while (s < longPause && isActive) {
                                                    val chunk = minOf(numSamples, longPause - s)
                                                    for (j in 0 until chunk) buffer[j] = 0
                                                    tr.write(buffer, 0, chunk)
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
                                                            buffer[j] = (Math.sin(notePhase) * 18000 * env).toInt().toShort()
                                                            notePhase += 2 * Math.PI * note / sampleRate
                                                        }
                                                        tr.write(buffer, 0, chunk)
                                                        k += chunk
                                                    }
                                                }
                                                val chimePause = (sampleRate * 0.8).toInt()
                                                var s = 0
                                                while (s < chimePause && isActive) {
                                                    val chunk = minOf(numSamples, chimePause - s)
                                                    for (j in 0 until chunk) buffer[j] = 0
                                                    tr.write(buffer, 0, chunk)
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
                                                        buffer[j] = (Math.sin(phase) * 15000).toInt().toShort()
                                                        phase += 2 * Math.PI * sweep / sampleRate
                                                    }
                                                    tr.write(buffer, 0, chunk)
                                                    k += chunk
                                                }
                                            }
                                        }
                                    }
                                    tr.stop()
                                    tr.release()
                                } catch (e: Exception) {
                                    Log.e("SynthPreview", "Synth audio generation failed", e)
                                } finally {
                                    if (currentlyPlayingUri == uriString) {
                                        currentlyPlayingUri = null
                                    }
                                }
                            }
                        } else {
                            try {
                                val player = MediaPlayer()
                                val uri = when (uriString) {
                                    "system_alarm" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                    "system_ringtone" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                                    "system_notification" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                    else -> Uri.parse(uriString)
                                }
                                player.setDataSource(context, uri)
                                player.setAudioStreamType(AudioManager.STREAM_MUSIC)
                                player.prepare()
                                player.start()
                                previewPlayer = player
                                
                                scope.launch {
                                    delay(4000)
                                    if (currentlyPlayingUri == uriString) {
                                        stopPreview()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("AudioSystemPreview", "Failed to preview default alarm", e)
                            }
                        }
                    }

                    AlertDialog(
                        onDismissRequest = {
                            stopPreview()
                            showSoundDialog = false
                        },
                        title = {
                            Text("Select Alarm Sound", color = OffWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 350.dp)
                            ) {
                                Text(
                                    "Select any of the exclusive synthesized premium tones or system alert files. Touch standard play icon to preview sound.",
                                    color = GhostText,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(availableSounds) { soundItem ->
                                        val isSelected = soundUri == soundItem.second
                                        val isPlaying = currentlyPlayingUri == soundItem.second

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSelected) CosmicBlue.copy(alpha = 0.25f) else LightSlateSurface)
                                                .clickable {
                                                    soundName = soundItem.first
                                                    soundUri = soundItem.second
                                                    playPreview(soundItem.second)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = if (isPlaying) Icons.Default.Refresh else Icons.Default.PlayArrow,
                                                    contentDescription = "Preview icon",
                                                    tint = if (isSelected) ElectricTeal else OffWhite,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clickable {
                                                            playPreview(soundItem.second)
                                                        }
                                                )
                                                Text(
                                                    text = soundItem.first,
                                                    color = if (isSelected) ElectricTeal else OffWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    stopPreview()
                                    showSoundDialog = false
                                }
                            ) {
                                Text("Done", color = SuccessGreen, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    stopPreview()
                                    showSoundDialog = false
                                }
                            ) {
                                Text("Cancel", color = GhostText)
                            }
                        },
                        containerColor = SlateSurface,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveAlarmOverlay(
    alarm: AlarmEntity,
    onSolved: () -> Unit,
    onSnoozed: () -> Unit
) {
    var puzzleState by rememberSaveable(stateSaver = MathPuzzleSaver) { mutableStateOf(MathPuzzleEngine.generatePuzzle(alarm.difficulty, alarm.easyJustAddition)) }
    var currentCount by rememberSaveable { mutableStateOf(0) }
    var currentInput by rememberSaveable { mutableStateOf("") }
    var shakeTrigger by rememberSaveable { mutableStateOf(false) }
    val totalRequired = alarm.questionCount
    val context = LocalContext.current

    var lastTapTrigger by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(lastTapTrigger) {
        if (lastTapTrigger > 0) {
            val duckIntent = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DUCK
            }
            context.startService(duckIntent)

            delay(2000)

            val unduckIntent = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_UNDUCK
            }
            context.startService(unduckIntent)
        }
    }

    // Memory cards states
    var memoryCards by rememberSaveable(stateSaver = MemoryCardListSaver) { mutableStateOf(MemoryPuzzleEngine.generateCards(alarm.difficulty)) }
    var memoryFlippedIndices by rememberSaveable(stateSaver = IntListSaver) { mutableStateOf(emptyList<Int>()) }

    // Sequence (Simon) states
    var simonSequence by rememberSaveable(stateSaver = IntListSaver) { mutableStateOf(SequencePuzzleEngine.generateSequence(alarm.difficulty)) }
    var activeFlashingIndex by rememberSaveable { mutableStateOf(-1) }
    var userSequenceIndex by rememberSaveable { mutableStateOf(0) }
    var isPlaybackPlaying by rememberSaveable { mutableStateOf(true) }

    fun triggerVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val man = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            man?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(300)
    }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger) {
            delay(500)
            shakeTrigger = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoidBlack, DeepSlateBg)))
            .padding(24.dp)
            .testTag("active_alarm_overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Elegant Dark active alarm header
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(CosmicBlue)
                    )
                    Text(
                        text = "ACTIVE ALARM",
                        color = CosmicBlue,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
                
                val timeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Calendar.getInstance().time).uppercase()
                Text(
                    text = timeString,
                    color = GhostText,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = if (alarm.label.isNotBlank()) alarm.label else "Wake Up",
                color = CosmicBlue,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp,
                lineHeight = 38.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                text = "Security Mode: preferred ${alarm.puzzleType} Lockdown Enabled",
                color = GhostText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )
                val gameMode = alarm.puzzleType.uppercase()

        if (gameMode == "MATH") {
            // Problem Display Card area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PUZZLE ${currentCount + 1} OF $totalRequired",
                                color = CosmicBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) CosmicBlue else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = puzzleState.question,
                            color = if (shakeTrigger) ErrorRed else OffWhite,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .widthIn(min = 100.dp)
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = currentInput.ifEmpty { "..." },
                                    color = if (currentInput.isEmpty()) GhostText else CosmicBlue,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.testTag("math_puzzle_input_display")
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .height(2.dp)
                                        .background(CosmicBlue)
                                )
                            }
                        }
                    }
                }
            }

            // Warning lockdown message
            Text(
                text = if (alarm.snoozeModeCode == "NONE" || alarm.snoozedCount >= alarm.maxSnoozes) {
                    "SNOOZE DISABLED • APP LOCKED UNTIL SOLVED"
                } else {
                    "SNOOZE PENALTY MODE • APP LOCKED UNTIL SOLVED"
                },
                color = ErrorRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Custom high contrast keypad
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("Del", "0", "Verify")
                )

                keys.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowKeys.forEach { key ->
                            val isVerify = key == "Verify"
                            val isDel = key == "Del"

                            val btnBgColor = when {
                                isVerify -> CosmicBlue
                                isDel -> Color(0xFF311111)
                                else -> SlateSurface
                            }

                            val btnTextColor = when {
                                isVerify -> Color(0xFF381E72)
                                isDel -> ErrorRed
                                else -> OffWhite
                            }

                            val shape = RoundedCornerShape(16.dp)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(60.dp)
                                    .clip(shape)
                                    .background(btnBgColor)
                                    .clickable {
                                        lastTapTrigger++
                                        when (key) {
                                            "Del" -> {
                                                if (currentInput.isNotEmpty()) {
                                                    currentInput = currentInput.dropLast(1)
                                                }
                                            }
                                            "Verify" -> {
                                                val inputVal = currentInput.toIntOrNull()
                                                val expectedAns = puzzleState.answer
                                                val questionStr = puzzleState.question
                                                Log.d("AlarmMathOverlay", "Verify clicked. Input: $inputVal, Expected: $expectedAns, Question: $questionStr")
                                                if (inputVal != null && inputVal == expectedAns) {
                                                    currentCount++
                                                    currentInput = ""
                                                    if (currentCount >= totalRequired) {
                                                        onSolved()
                                                    } else {
                                                        puzzleState = MathPuzzleEngine.generatePuzzle(alarm.difficulty, alarm.easyJustAddition)
                                                    }
                                                } else {
                                                    currentCount = 0
                                                    currentInput = ""
                                                    shakeTrigger = true
                                                    triggerVibration()
                                                    Toast.makeText(
                                                        context,
                                                        "Incorrect! Expected $expectedAns for \"$questionStr\" (Sequence reset)",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            else -> {
                                                if (currentInput.length < 5) {
                                                    currentInput += key
                                                }
                                            }
                                        }
                                    }
                                    .testTag("keypad_btn_$key"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isVerify) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Verify",
                                        tint = btnTextColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else if (isDel) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = btnTextColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        color = btnTextColor,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (gameMode == "MEMORY") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BOARD ${currentCount + 1} OF $totalRequired",
                                color = ElectricTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) ElectricTeal else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Match the Pairs",
                            color = OffWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        val cols = if (alarm.difficulty.uppercase() == "EASY") 3 else 4
                        val rows = memoryCards.chunked(cols)
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rows.forEach { rowCards ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowCards.forEach { card ->
                                        val index = memoryCards.indexOfFirst { it.id == card.id }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (card.isFlipped || card.isMatched) LightSlateSurface else SlateSurface)
                                                .border(
                                                    width = 2.dp,
                                                    color = when {
                                                        card.isMatched -> SuccessGreen
                                                        card.isFlipped -> CosmicBlue
                                                        else -> LightSlateSurface
                                                    },
                                                    shape = RoundedCornerShape(16.dp)
                                                )
                                                .clickable(enabled = !card.isFlipped && !card.isMatched && memoryFlippedIndices.size < 2) {
                                                    if (index >= 0) {
                                                        memoryCards = memoryCards.mapIndexed { idx, c ->
                                                            if (idx == index) c.copy(isFlipped = true) else c
                                                        }
                                                        memoryFlippedIndices = memoryFlippedIndices + index
                                                    }
                                                }
                                                .testTag("memory_card_${card.id}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (card.isFlipped || card.isMatched) {
                                                Text(text = card.symbol, fontSize = 28.sp)
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "Hidden",
                                                    tint = GhostText,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val allMatched = memoryCards.isNotEmpty() && memoryCards.all { it.isMatched }
                        LaunchedEffect(allMatched) {
                            if (allMatched) {
                                delay(600)
                                currentCount++
                                triggerVibration()
                                if (currentCount >= totalRequired) {
                                    onSolved()
                                } else {
                                    memoryCards = MemoryPuzzleEngine.generateCards(alarm.difficulty)
                                    memoryFlippedIndices = emptyList()
                                }
                            }
                        }

                        LaunchedEffect(memoryFlippedIndices) {
                            if (memoryFlippedIndices.size == 2) {
                                val firstIdx = memoryFlippedIndices[0]
                                val secondIdx = memoryFlippedIndices[1]
                                delay(800)
                                if (firstIdx in memoryCards.indices && secondIdx in memoryCards.indices) {
                                    if (memoryCards[firstIdx].symbol == memoryCards[secondIdx].symbol) {
                                        memoryCards = memoryCards.mapIndexed { idx, card ->
                                            if (idx == firstIdx || idx == secondIdx) {
                                                card.copy(isMatched = true, isFlipped = true)
                                            } else {
                                                card
                                            }
                                        }
                                    } else {
                                        memoryCards = memoryCards.mapIndexed { idx, card ->
                                            if (idx == firstIdx || idx == secondIdx) {
                                                card.copy(isFlipped = false)
                                            } else {
                                                card
                                            }
                                        }
                                    }
                                }
                                memoryFlippedIndices = emptyList()
                            }
                        }
                    }
                }
            }

            // Warning lockdown message
            Text(
                text = if (alarm.snoozeModeCode == "NONE" || alarm.snoozedCount >= alarm.maxSnoozes) {
                    "SNOOZE DISABLED • APP LOCKED UNTIL SOLVED"
                } else {
                    "SNOOZE PENALTY MODE • APP LOCKED UNTIL SOLVED"
                },
                color = ErrorRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PATTERN ${currentCount + 1} OF $totalRequired",
                                color = AmberRamping,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) AmberRamping else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (isPlaybackPlaying) "Watch carefully..." else "Repeat Pattern (${userSequenceIndex}/${simonSequence.size})",
                            color = if (isPlaybackPlaying) AmberRamping else ElectricTeal,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SimonPad(
                                    index = 0,
                                    isFlashing = activeFlashingIndex == 0,
                                    color = CosmicBlue,
                                    icon = Icons.Default.Lock,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 0 == simonSequence[userSequenceIndex]) {
                                                triggerVibration()
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        onSolved()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(alarm.difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                triggerVibration()
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                                SimonPad(
                                    index = 1,
                                    isFlashing = activeFlashingIndex == 1,
                                    color = AmberRamping,
                                    icon = Icons.Default.Check,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 1 == simonSequence[userSequenceIndex]) {
                                                triggerVibration()
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        onSolved()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(alarm.difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                triggerVibration()
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SimonPad(
                                    index = 2,
                                    isFlashing = activeFlashingIndex == 2,
                                    color = ElectricTeal,
                                    icon = Icons.Default.PlayArrow,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 2 == simonSequence[userSequenceIndex]) {
                                                triggerVibration()
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        onSolved()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(alarm.difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                triggerVibration()
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                                SimonPad(
                                    index = 3,
                                    isFlashing = activeFlashingIndex == 3,
                                    color = ErrorRed,
                                    icon = Icons.Default.Refresh,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 3 == simonSequence[userSequenceIndex]) {
                                                triggerVibration()
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        onSolved()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(alarm.difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                triggerVibration()
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        Button(
                            onClick = { isPlaybackPlaying = true },
                            colors = ButtonDefaults.buttonColors(containerColor = LightSlateSurface),
                            enabled = !isPlaybackPlaying,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Replay Pattern", color = OffWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        LaunchedEffect(simonSequence, isPlaybackPlaying) {
                            if (isPlaybackPlaying) {
                                userSequenceIndex = 0
                                delay(600)
                                for (value in simonSequence) {
                                    activeFlashingIndex = value
                                    delay(500)
                                    activeFlashingIndex = -1
                                    delay(200)
                                }
                                isPlaybackPlaying = false
                            }
                        }
                    }
                }
            }

            // Warning lockdown message
            Text(
                text = if (alarm.snoozeModeCode == "NONE" || alarm.snoozedCount >= alarm.maxSnoozes) {
                    "SNOOZE DISABLED • APP LOCKED UNTIL SOLVED"
                } else {
                    "SNOOZE PENALTY MODE • APP LOCKED UNTIL SOLVED"
                },
                color = ErrorRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

            // Bottom bar actions row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = GhostText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "DEVICE LOCKED",
                        color = GhostText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                val isSnoozeAllowed = alarm.snoozeModeCode == "PENALTY" && alarm.snoozedCount < alarm.maxSnoozes
                
                if (isSnoozeAllowed) {
                    val remainingSnoozes = alarm.maxSnoozes - alarm.snoozedCount
                    Button(
                        onClick = onSnoozed,
                        colors = ButtonDefaults.buttonColors(containerColor = LightSlateSurface, contentColor = OffWhite),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("snooze_alarm_button")
                    ) {
                        Text(
                            text = "SNOOZE ($remainingSnoozes LEFT)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    Button(
                        onClick = {},
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = LightSlateSurface.copy(alpha = 0.5f),
                            disabledContentColor = GhostText
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("snooze_alarm_button")
                    ) {
                        Text(
                            text = "SNOOZE LOCKED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PracticeOverlay(
    difficulty: String,
    onDismiss: () -> Unit
) {
    var practiceType by rememberSaveable { mutableStateOf("MATH") }
    var practiceJustAddition by rememberSaveable { mutableStateOf(false) }
    var puzzleState by rememberSaveable(stateSaver = MathPuzzleSaver) { mutableStateOf(MathPuzzleEngine.generatePuzzle(difficulty, false)) }
    var currentCount by rememberSaveable { mutableStateOf(0) }
    var currentInput by rememberSaveable { mutableStateOf("") }
    var shakeTrigger by rememberSaveable { mutableStateOf(false) }
    val totalRequired = 3
    val context = LocalContext.current

    // Memory cards states for practice
    var memoryCards by rememberSaveable(stateSaver = MemoryCardListSaver) { mutableStateOf(MemoryPuzzleEngine.generateCards(difficulty)) }
    var memoryFlippedIndices by rememberSaveable(stateSaver = IntListSaver) { mutableStateOf(emptyList<Int>()) }

    // Sequence (Simon) states for practice
    var simonSequence by rememberSaveable(stateSaver = IntListSaver) { mutableStateOf(SequencePuzzleEngine.generateSequence(difficulty)) }
    var activeFlashingIndex by rememberSaveable { mutableStateOf(-1) }
    var userSequenceIndex by rememberSaveable { mutableStateOf(0) }
    var isPlaybackPlaying by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger) {
            delay(500)
            shakeTrigger = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoidBlack, DeepSlateBg)))
            .padding(24.dp)
            .testTag("practice_overlay"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Elegant Dark practice header
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_practice_button")) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Exit", tint = OffWhite)
                }
                
                Text(
                    text = "Sandbox Practice",
                    color = CosmicBlue,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                IconButton(onClick = {
                    puzzleState = MathPuzzleEngine.generatePuzzle(difficulty, practiceJustAddition)
                    currentInput = ""
                    currentCount = 0
                }) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry", tint = OffWhite)
                }
            }
            Text(
                text = "Cognitive Practice: $difficulty",
                color = CosmicBlue,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp,
                lineHeight = 34.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                text = "Train your morning waking focus with simulated equations.",
                color = GhostText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("MATH", "MEMORY", "SEQUENCE").forEach { type ->
                    val selected = practiceType == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) CosmicBlue else SlateSurface)
                            .border(1.dp, if (selected) CosmicBlue else LightSlateSurface, RoundedCornerShape(12.dp))
                            .clickable {
                                practiceType = type
                                currentCount = 0
                                currentInput = ""
                                memoryCards = MemoryPuzzleEngine.generateCards(difficulty)
                                memoryFlippedIndices = emptyList()
                                simonSequence = SequencePuzzleEngine.generateSequence(difficulty)
                                isPlaybackPlaying = true
                            }
                            .padding(vertical = 8.dp)
                            .testTag("practice_type_chip_$type"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            color = if (selected) Color(0xFF381E72) else OffWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            } // Close the Row containing MATH MEMORY SEQUENCE
        } // Close Header Column

            if (practiceType == "MATH") {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (difficulty.uppercase() == "EASY") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .clip(RoundedCornerShape(12.dp))
                                  .background(SlateSurface)
                                  .clickable {
                                      practiceJustAddition = !practiceJustAddition
                                      puzzleState = MathPuzzleEngine.generatePuzzle(difficulty, practiceJustAddition)
                                      currentCount = 0
                                      currentInput = ""
                                  }
                                  .padding(horizontal = 16.dp, vertical = 10.dp),
                              horizontalArrangement = Arrangement.SpaceBetween,
                              verticalAlignment = Alignment.CenterVertically
                          ) {
                              Row(
                                  verticalAlignment = Alignment.CenterVertically,
                                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                                  modifier = Modifier.weight(1f) // Ensure text column doesn't get squeezed horizontally
                              ) {
                                  Icon(
                                      imageVector = Icons.Default.Add,
                                      contentDescription = null,
                                      tint = SuccessGreen,
                                      modifier = Modifier.size(18.dp)
                                  )
                                  Column {
                                      Text("Just Addition", color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                      Text("Disable subtraction for Easy practice", color = GhostText, fontSize = 10.sp)
                                  }
                              }
                              Switch(
                                  checked = practiceJustAddition,
                                  onCheckedChange = {
                                      practiceJustAddition = it
                                      puzzleState = MathPuzzleEngine.generatePuzzle(difficulty, it)
                                      currentCount = 0
                                      currentInput = ""
                                  },
                                  colors = SwitchDefaults.colors(
                                      checkedThumbColor = SuccessGreen,
                                      checkedTrackColor = SuccessGreen.copy(alpha = 0.5f)
                                  ),
                                  modifier = Modifier.testTag("practice_just_addition_switch")
                              )
                          }
                      }

            // Problem Display Card area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .weight(1f, fill = false),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EQUATION ${currentCount + 1} OF $totalRequired",
                                color = CosmicBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) CosmicBlue else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = puzzleState.question,
                            color = if (shakeTrigger) ErrorRed else OffWhite,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .widthIn(min = 100.dp)
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = currentInput.ifEmpty { "..." },
                                    color = if (currentInput.isEmpty()) GhostText else CosmicBlue,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.testTag("math_puzzle_input_display")
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .height(2.dp)
                                        .background(CosmicBlue)
                                )
                            }
                        }
                    }
                }
            }

            // Warning or helpful instruction
            Text(
                text = "SOLVE 3 CONSECUTIVE PUZZLES TO ENERGIZE THE PREFRONTAL CORTEX",
                color = CosmicBlue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Custom high contrast keypad
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("Del", "0", "Verify")
                )

                keys.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowKeys.forEach { key ->
                            val isVerify = key == "Verify"
                            val isDel = key == "Del"

                            val btnBgColor = when {
                                isVerify -> CosmicBlue
                                isDel -> Color(0xFF311111)
                                else -> SlateSurface
                            }

                            val btnTextColor = when {
                                isVerify -> Color(0xFF381E72)
                                isDel -> ErrorRed
                                else -> OffWhite
                            }

                            val shape = RoundedCornerShape(16.dp)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(60.dp)
                                    .clip(shape)
                                    .background(btnBgColor)
                                    .clickable {
                                        when (key) {
                                            "Del" -> {
                                                if (currentInput.isNotEmpty()) {
                                                    currentInput = currentInput.dropLast(1)
                                                }
                                            }
                                            "Verify" -> {
                                                val inputVal = currentInput.toIntOrNull()
                                                val expectedAns = puzzleState.answer
                                                val questionStr = puzzleState.question
                                                Log.d("AlarmMathPractice", "Verify clicked. Input: $inputVal, Expected: $expectedAns, Question: $questionStr")
                                                if (inputVal != null && inputVal == expectedAns) {
                                                    currentCount++
                                                    currentInput = ""
                                                    if (currentCount >= totalRequired) {
                                                        Toast.makeText(context, "Practice completed! Brain woke up successfully.", Toast.LENGTH_SHORT).show()
                                                        onDismiss()
                                                    } else {
                                                        puzzleState = MathPuzzleEngine.generatePuzzle(difficulty, practiceJustAddition)
                                                    }
                                                } else {
                                                    currentCount = 0
                                                    currentInput = ""
                                                    shakeTrigger = true
                                                    Toast.makeText(
                                                        context,
                                                        "Incorrect! Expected $expectedAns for \"$questionStr\" (Sequence reset)",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            else -> {
                                                if (currentInput.length < 5) {
                                                    currentInput += key
                                                }
                                            }
                                        }
                                    }
                                    .testTag("practice_keypad_$key"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isVerify) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Verify",
                                        tint = btnTextColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else if (isDel) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = btnTextColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        color = btnTextColor,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    } // end Row
                } // end keys.forEach
            } // end Column (keypad)
        } // end Column (modifier = Modifier.weight(1f))
        } else if (practiceType == "MEMORY") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BOARD ${currentCount + 1} OF $totalRequired",
                                color = ElectricTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) ElectricTeal else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "Match the Pairs",
                            color = OffWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        val cols = if (difficulty.uppercase() == "EASY") 3 else 4
                        val rows = memoryCards.chunked(cols)
                        Column(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rows.forEach { rowCards ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowCards.forEach { card ->
                                        val index = memoryCards.indexOfFirst { it.id == card.id }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(if (card.isFlipped || card.isMatched) LightSlateSurface else SlateSurface)
                                                .border(
                                                    width = 2.dp,
                                                    color = when {
                                                        card.isMatched -> SuccessGreen
                                                        card.isFlipped -> CosmicBlue
                                                        else -> LightSlateSurface
                                                    },
                                                    shape = RoundedCornerShape(16.dp)
                                                )
                                                .clickable(enabled = !card.isFlipped && !card.isMatched && memoryFlippedIndices.size < 2) {
                                                    if (index >= 0) {
                                                        memoryCards = memoryCards.mapIndexed { idx, c ->
                                                            if (idx == index) c.copy(isFlipped = true) else c
                                                        }
                                                        memoryFlippedIndices = memoryFlippedIndices + index
                                                    }
                                                }
                                                .testTag("practice_memory_card_${card.id}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (card.isFlipped || card.isMatched) {
                                                Text(text = card.symbol, fontSize = 28.sp)
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "Hidden",
                                                    tint = GhostText,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val allMatched = memoryCards.isNotEmpty() && memoryCards.all { it.isMatched }
                        LaunchedEffect(allMatched) {
                            if (allMatched) {
                                delay(600)
                                currentCount++
                                if (currentCount >= totalRequired) {
                                    Toast.makeText(context, "Practice completed! Pairing brain paths successfully.", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } else {
                                    memoryCards = MemoryPuzzleEngine.generateCards(difficulty)
                                    memoryFlippedIndices = emptyList()
                                }
                            }
                        }

                        LaunchedEffect(memoryFlippedIndices) {
                            if (memoryFlippedIndices.size == 2) {
                                val firstIdx = memoryFlippedIndices[0]
                                val secondIdx = memoryFlippedIndices[1]
                                delay(800)
                                if (firstIdx in memoryCards.indices && secondIdx in memoryCards.indices) {
                                    if (memoryCards[firstIdx].symbol == memoryCards[secondIdx].symbol) {
                                        memoryCards = memoryCards.mapIndexed { idx, card ->
                                            if (idx == firstIdx || idx == secondIdx) {
                                                card.copy(isMatched = true, isFlipped = true)
                                            } else {
                                                card
                                            }
                                        }
                                    } else {
                                        memoryCards = memoryCards.mapIndexed { idx, card ->
                                            if (idx == firstIdx || idx == secondIdx) {
                                                card.copy(isFlipped = false)
                                            } else {
                                                card
                                            }
                                        }
                                    }
                                }
                                memoryFlippedIndices = emptyList()
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightSlateSurface)
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val progressFraction = if (totalRequired > 0) currentCount.toFloat() / totalRequired.toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(LightSlateSurface)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .fillMaxHeight()
                                .background(CosmicBlue)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PATTERN ${currentCount + 1} OF $totalRequired",
                                color = AmberRamping,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (i in 0 until totalRequired) {
                                    val completed = i < currentCount
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (completed) AmberRamping else LightSlateSurface)
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (isPlaybackPlaying) "Watch carefully..." else "Repeat Pattern (${userSequenceIndex}/${simonSequence.size})",
                            color = if (isPlaybackPlaying) AmberRamping else ElectricTeal,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SimonPad(
                                    index = 0,
                                    isFlashing = activeFlashingIndex == 0,
                                    color = CosmicBlue,
                                    icon = Icons.Default.Lock,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 0 == simonSequence[userSequenceIndex]) {
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        Toast.makeText(context, "Sequence Completed! Focus achieved.", Toast.LENGTH_SHORT).show()
                                                        onDismiss()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                                SimonPad(
                                    index = 1,
                                    isFlashing = activeFlashingIndex == 1,
                                    color = AmberRamping,
                                    icon = Icons.Default.Check,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 1 == simonSequence[userSequenceIndex]) {
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        Toast.makeText(context, "Sequence Completed! Focus achieved.", Toast.LENGTH_SHORT).show()
                                                        onDismiss()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SimonPad(
                                    index = 2,
                                    isFlashing = activeFlashingIndex == 2,
                                    color = ElectricTeal,
                                    icon = Icons.Default.PlayArrow,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 2 == simonSequence[userSequenceIndex]) {
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        Toast.makeText(context, "Sequence Completed! Focus achieved.", Toast.LENGTH_SHORT).show()
                                                        onDismiss()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                                SimonPad(
                                    index = 3,
                                    isFlashing = activeFlashingIndex == 3,
                                    color = ErrorRed,
                                    icon = Icons.Default.Refresh,
                                    onClick = {
                                        if (!isPlaybackPlaying) {
                                            if (userSequenceIndex in simonSequence.indices && 3 == simonSequence[userSequenceIndex]) {
                                                userSequenceIndex++
                                                if (userSequenceIndex >= simonSequence.size) {
                                                    currentCount++
                                                    if (currentCount >= totalRequired) {
                                                        Toast.makeText(context, "Sequence Completed! Focus achieved.", Toast.LENGTH_SHORT).show()
                                                        onDismiss()
                                                    } else {
                                                        simonSequence = SequencePuzzleEngine.generateSequence(difficulty)
                                                        isPlaybackPlaying = true
                                                    }
                                                }
                                            } else {
                                                shakeTrigger = true
                                                userSequenceIndex = 0
                                                currentCount = 0
                                                Toast.makeText(context, "Sequence reset to 0!", Toast.LENGTH_SHORT).show()
                                                isPlaybackPlaying = true
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        Button(
                            onClick = { isPlaybackPlaying = true },
                            colors = ButtonDefaults.buttonColors(containerColor = LightSlateSurface),
                            enabled = !isPlaybackPlaying,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Replay Pattern", color = OffWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        LaunchedEffect(simonSequence, isPlaybackPlaying) {
                            if (isPlaybackPlaying) {
                                userSequenceIndex = 0
                                delay(600)
                                for (value in simonSequence) {
                                    activeFlashingIndex = value
                                    delay(500)
                                    activeFlashingIndex = -1
                                    delay(200)
                                }
                                isPlaybackPlaying = false
                            }
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom bar actions row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Sandbox",
                        tint = GhostText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "SANDBOX PRACTICE MODE",
                        color = GhostText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = LightSlateSurface, contentColor = OffWhite),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("dismiss_practice_button")
                ) {
                    Text(
                        text = "EXIT PRACTICE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }

@Composable
fun RowScope.SimonPad(
    index: Int,
    isFlashing: Boolean,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val alpha = if (isFlashing) 1f else 0.25f
    val scale = if (isFlashing) 1.05f else 1f
    val animatedAlpha by animateFloatAsState(targetValue = alpha, label = "alpha")
    val animatedScale by animateFloatAsState(targetValue = scale, label = "scale")

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1.2f)
            .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = animatedAlpha))
            .border(2.dp, if (isFlashing) color else color.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("simon_pad_$index"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Simon Color Pad $index",
            tint = if (isFlashing) Color.White else OffWhite.copy(alpha = 0.7f),
            modifier = Modifier.size(32.dp)
        )
    }
}
