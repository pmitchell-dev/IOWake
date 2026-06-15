package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val label: String = "Alarm",
    val hour: Int,
    val minute: Int,
    val repeatDaysCode: String = "", // Comma-separated day numbers (1 = Sunday, 2 = Monday, ..., 7 = Saturday)
    val difficulty: String = "MEDIUM", // EASY, MEDIUM, HARD
    val questionCount: Int = 3, // Consecutive correct math problems to solve
    val snoozeModeCode: String = "PENALTY", // NONE, PENALTY
    val maxSnoozes: Int = 2, // Maximum snoozes allowed
    val snoozeIntervalMinutes: Int = 10, // Initial snooze interval in minutes
    val snoozedCount: Int = 0, // Alarm state tracker
    val volumeRamping: Boolean = true, // Whether to play with progressive ramping volume
    val puzzleType: String = "MATH", // MATH, MEMORY, SEQUENCE
    val easyJustAddition: Boolean = false, // If true, EASY math puzzles only have addition
    val soundName: String = "Classic Digital Beep",
    val soundUri: String = "synth_digital_beep",
    val isEnabled: Boolean = true
) {
    // Helper to parser repeat days
    fun getRepeatDays(): Set<Int> {
        if (repeatDaysCode.isBlank()) return emptySet()
        return repeatDaysCode.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }
}
