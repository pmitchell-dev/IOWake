package com.example.alarm

import com.example.data.database.AlarmEntity
import kotlinx.coroutines.flow.MutableStateFlow

object AlarmStateHolder {
    // Exposes the currently active ringing alarm so the UI can instantly switch to Math Puzzle mode
    val activeRingingAlarm = MutableStateFlow<AlarmEntity?>(null)
}
