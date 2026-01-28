package app.solarma.alarm

import android.util.Log
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for alarm operations.
 * Wraps AlarmDao with scheduling and business logic.
 */
@Singleton
class AlarmRepository @Inject constructor(
    private val alarmDao: AlarmDao,
    private val alarmScheduler: AlarmScheduler,
    private val statsDao: StatsDao
) {
    companion object {
        private const val TAG = "Solarma.AlarmRepository"
    }
    
    /**
     * Create a new alarm: save to DB and schedule with AlarmManager.
     * @return Alarm ID
     */
    suspend fun createAlarm(alarm: AlarmEntity): Long {
        // Insert into database
        val id = alarmDao.insert(alarm)
        Log.i(TAG, "Alarm created: id=$id, time=${alarm.alarmTimeMillis}")
        
        // Schedule with AlarmManager
        if (alarm.isEnabled) {
            alarmScheduler.schedule(id, alarm.alarmTimeMillis)
        }
        
        // Update stats
        ensureStatsExist()
        
        return id
    }
    
    /**
     * Get alarm by ID.
     */
    suspend fun getAlarm(id: Long): AlarmEntity? {
        return alarmDao.getById(id)
    }
    
    /**
     * Get all active (enabled) alarms.
     */
    fun getActiveAlarms(): Flow<List<AlarmEntity>> {
        return alarmDao.getEnabledAlarms()
    }
    
    /**
     * Get all alarms.
     */
    fun getAllAlarms(): Flow<List<AlarmEntity>> {
        return alarmDao.getAllAlarms()
    }
    
    /**
     * Enable or disable an alarm.
     */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        alarmDao.setEnabled(id, enabled)
        
        val alarm = alarmDao.getById(id) ?: return
        
        if (enabled) {
            alarmScheduler.schedule(id, alarm.alarmTimeMillis)
        } else {
            alarmScheduler.cancel(id)
        }
        
        Log.i(TAG, "Alarm $id enabled=$enabled")
    }
    
    /**
     * Mark alarm as triggered.
     */
    suspend fun markTriggered(id: Long) {
        val timestamp = System.currentTimeMillis()
        alarmDao.updateLastTriggered(id, timestamp)
        Log.i(TAG, "Alarm $id triggered at $timestamp")
    }
    
    /**
     * Mark alarm as completed (wake proof passed).
     */
    suspend fun markCompleted(id: Long, success: Boolean) {
        if (success) {
            // Record successful wake
            statsDao.recordSuccessfulWake(System.currentTimeMillis())
            Log.i(TAG, "Alarm $id completed successfully")
        } else {
            // Reset streak on failure
            statsDao.resetStreak()
            Log.i(TAG, "Alarm $id failed, streak reset")
        }
        
        // Disable one-time alarm (for repeating, would update next ring)
        val alarm = alarmDao.getById(id) ?: return
        if (alarm.repeatDays == 0) {
            alarmDao.setEnabled(id, false)
        }
    }
    
    /**
     * Update next ring time (for snooze or repeat).
     */
    suspend fun updateNextRing(id: Long, nextAtMillis: Long) {
        val alarm = alarmDao.getById(id) ?: return
        alarmDao.update(alarm.copy(alarmTimeMillis = nextAtMillis))
        alarmScheduler.schedule(id, nextAtMillis)
        Log.i(TAG, "Alarm $id rescheduled to $nextAtMillis")
    }
    
    /**
     * Handle snooze: reschedule alarm, increment stats.
     */
    suspend fun snooze(id: Long, snoozeMinutes: Int = 5) {
        val nextRing = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
        updateNextRing(id, nextRing)
        statsDao.incrementSnoozes()
        Log.i(TAG, "Alarm $id snoozed for $snoozeMinutes minutes")
    }
    
    /**
     * Delete an alarm.
     */
    suspend fun deleteAlarm(id: Long) {
        alarmScheduler.cancel(id)
        alarmDao.deleteById(id)
        Log.i(TAG, "Alarm $id deleted")
    }
    
    /**
     * Restore all alarms after boot.
     * Called from BootReceiver/WorkManager.
     */
    suspend fun restoreAllAlarms() {
        val alarms = alarmDao.getEnabledAlarms().first()
        val now = System.currentTimeMillis()
        
        for (alarm in alarms) {
            if (alarm.alarmTimeMillis > now) {
                alarmScheduler.schedule(alarm.id, alarm.alarmTimeMillis)
                Log.d(TAG, "Restored alarm ${alarm.id}")
            } else {
                // Alarm time passed while device was off
                Log.w(TAG, "Alarm ${alarm.id} missed while device was off")
                // Could trigger immediately or mark as missed
            }
        }
        
        Log.i(TAG, "Restored ${alarms.size} alarms")
    }
    
    /**
     * Ensure stats row exists.
     */
    private suspend fun ensureStatsExist() {
        if (statsDao.getStatsOnce() == null) {
            statsDao.insert(StatsEntity())
        }
    }
}
