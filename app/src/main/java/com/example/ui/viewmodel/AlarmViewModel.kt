package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AlarmDatabase
import com.example.data.database.AlarmEntity
import com.example.data.repository.AlarmRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmViewModel(private val repository: AlarmRepository) : ViewModel() {

    val alarms: StateFlow<List<AlarmEntity>> = repository.allAlarms
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _editingAlarm = MutableStateFlow<AlarmEntity?>(null)
    val editingAlarm: StateFlow<AlarmEntity?> = _editingAlarm.asStateFlow()

    init {
        // Pre-populate with typical alarms if empty to prevent empty screen slop on first run
        viewModelScope.launch {
            repository.allAlarms.collect { list ->
                if (list.isEmpty()) {
                    prepopulateSampleAlarms()
                }
            }
        }
    }

    private suspend fun prepopulateSampleAlarms() {
        val workdaysAlarm = AlarmEntity(
            label = "Morning Brain Booster",
            hour = 7,
            minute = 0,
            repeatDaysCode = "2,3,4,5,6", // Mon - Fri
            difficulty = "MEDIUM",
            questionCount = 3,
            snoozeModeCode = "PENALTY",
            maxSnoozes = 2,
            volumeRamping = true,
            isEnabled = false // Let user activate it manually to avoid sudden blaring
        )
        val weekendAlarm = AlarmEntity(
            label = "Weekend Lie-in Guard",
            hour = 9,
            minute = 30,
            repeatDaysCode = "1,7", // Sun, Sat
            difficulty = "EASY",
            questionCount = 2,
            snoozeModeCode = "NONE", // No snooze allowed on weekend wake-up
            maxSnoozes = 0,
            volumeRamping = true,
            isEnabled = false
        )
        repository.insert(workdaysAlarm)
        repository.insert(weekendAlarm)
    }

    fun setEditingAlarm(alarm: AlarmEntity?) {
        _editingAlarm.value = alarm
    }

    fun saveAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            if (alarm.id == 0) {
                repository.insert(alarm)
            } else {
                repository.update(alarm)
            }
            _editingAlarm.value = null
        }
    }

    fun deleteAlarm(alarm: AlarmEntity) {
        viewModelScope.launch {
            repository.delete(alarm)
        }
    }

    fun toggleAlarm(alarm: AlarmEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleAlarm(alarm, enabled)
        }
    }
}

class AlarmViewModelFactory(private val repository: AlarmRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AlarmViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AlarmViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
