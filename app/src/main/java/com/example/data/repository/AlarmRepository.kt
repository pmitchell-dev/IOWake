package com.example.data.repository

import android.content.Context
import com.example.alarm.AlarmScheduler
import com.example.data.database.AlarmDao
import com.example.data.database.AlarmEntity
import kotlinx.coroutines.flow.Flow

class AlarmRepository(
    private val alarmDao: AlarmDao,
    context: Context
) {
    private val scheduler = AlarmScheduler(context.applicationContext)

    val allAlarms: Flow<List<AlarmEntity>> = alarmDao.getAllAlarms()

    suspend fun getAlarmById(id: Int): AlarmEntity? {
        return alarmDao.getAlarmById(id)
    }

    suspend fun insert(alarm: AlarmEntity): Int {
        val newId = alarmDao.insertAlarm(alarm).toInt()
        val alarmWithId = alarm.copy(id = newId)
        if (alarmWithId.isEnabled) {
            scheduler.schedule(alarmWithId)
        } else {
            scheduler.cancel(alarmWithId)
        }
        return newId
    }

    suspend fun update(alarm: AlarmEntity) {
        alarmDao.updateAlarm(alarm)
        if (alarm.isEnabled) {
            scheduler.schedule(alarm)
        } else {
            scheduler.cancel(alarm)
        }
    }

    suspend fun delete(alarm: AlarmEntity) {
        scheduler.cancel(alarm)
        alarmDao.deleteAlarm(alarm)
    }

    suspend fun toggleAlarm(alarm: AlarmEntity, enabled: Boolean) {
        val updated = alarm.copy(isEnabled = enabled, snoozedCount = 0)
        alarmDao.updateAlarm(updated)
        if (enabled) {
            scheduler.schedule(updated)
        } else {
            scheduler.cancel(updated)
        }
    }
}
