package app.solarma.alarm

import android.content.Context
import android.util.Log
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import app.solarma.wallet.OnchainAlarmParser
import app.solarma.wallet.SolanaRpcClient
import app.solarma.wallet.SolarmaInstructionBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Repository for alarm operations.
 * Wraps AlarmDao with scheduling and business logic.
 */
@Singleton
class AlarmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmDao: AlarmDao,
    private val alarmScheduler: AlarmScheduler,
    private val statsDao: StatsDao,
    private val transactionQueue: app.solarma.wallet.TransactionQueue,
    private val rpcClient: SolanaRpcClient
) {
    /**
     * Parser function for on-chain alarm data.
     * Defaults to OnchainAlarmParser::parse but can be overridden in tests.
     */
    internal var parseAlarm: (String, String) -> app.solarma.wallet.OnchainAlarmAccount? =
        app.solarma.wallet.OnchainAlarmParser::parse
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
        statsDao.incrementTotalAlarms()

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
            // If alarm time is in the past, advance to next occurrence
            var triggerTime = alarm.alarmTimeMillis
            val now = System.currentTimeMillis()
            if (triggerTime <= now) {
                val dayMs = 24 * 60 * 60 * 1000L
                while (triggerTime <= now) {
                    triggerTime += dayMs
                }
                alarmDao.update(alarm.copy(alarmTimeMillis = triggerTime))
                Log.i(TAG, "Alarm $id time advanced to $triggerTime (was in past)")
            }
            alarmScheduler.schedule(id, triggerTime)
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
        val alarm = alarmDao.getById(id)
        if (alarm == null) {
            Log.w(TAG, "Alarm $id not found for completion")
            return
        }
        ensureStatsExist()
        if (success) {
            // Record successful wake
            statsDao.recordSuccessfulWake(System.currentTimeMillis())
            alarmDao.updateLastCompleted(id, System.currentTimeMillis())
            Log.i(TAG, "Alarm $id completed successfully")
        } else {
            // Reset streak on failure
            statsDao.resetStreak()
            Log.i(TAG, "Alarm $id failed, streak reset")
        }

        // Handle one-time alarms
        if (alarm.repeatDays == 0) {
            if (success && !alarm.hasDeposit) {
                // One-time, no deposit: auto-delete
                alarmScheduler.cancel(id)
                alarmDao.deleteById(id)
                Log.i(TAG, "Alarm $id auto-deleted (one-time, no deposit, completed)")
            } else {
                // Has deposit: disable until claim/slash resolves
                alarmDao.setEnabled(id, false)
            }
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
    suspend fun snooze(id: Long, snoozeMinutes: Int = AlarmTiming.SNOOZE_MINUTES) {
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
    suspend fun restoreAllAlarms(): Int {
        val alarms = alarmDao.getEnabledAlarms().first()
        val now = System.currentTimeMillis()
        var missedCount = 0

        for (alarm in alarms) {
            if (alarm.alarmTimeMillis > now) {
                alarmScheduler.schedule(alarm.id, alarm.alarmTimeMillis)
                Log.d(TAG, "Restored alarm ${alarm.id}")
            } else {
                // Alarm time passed while device was off
                Log.w(TAG, "Alarm ${alarm.id} missed while device was off")
                missedCount++

                // Mark as failed and disable one-time alarms
                markCompleted(alarm.id, success = false)
                alarmDao.updateLastTriggered(alarm.id, alarm.alarmTimeMillis)

                if (alarm.repeatDays == 0) {
                    alarmDao.setEnabled(alarm.id, false)
                }

                // Note: if alarm.hasDeposit, funds are lost (penalty applied)
                if (alarm.hasDeposit) {
                    Log.e(TAG, "Alarm ${alarm.id} missed with deposit! Penalty will be applied.")
                    maybeQueueSlash(alarm)
                }
            }
        }

        Log.i(TAG, "Restored ${alarms.size - missedCount} alarms, $missedCount missed")
        return missedCount
    }

    /**
     * Ensure stats row exists.
     */
    private suspend fun ensureStatsExist() {
        if (statsDao.getStatsOnce() == null) {
            statsDao.insert(StatsEntity())
        }
    }

    private suspend fun maybeQueueSlash(alarm: AlarmEntity) {
        val deadlineMillis = alarm.alarmTimeMillis + AlarmTiming.GRACE_PERIOD_MILLIS
        if (System.currentTimeMillis() < deadlineMillis) {
            SlashAlarmWorker.enqueue(context, alarm.id, deadlineMillis - System.currentTimeMillis())
            return
        }

        val lastTriggered = alarm.lastTriggeredAt ?: alarm.alarmTimeMillis
        val completed = alarm.lastCompletedAt
        val completedAfterTrigger = completed != null && completed >= lastTriggered
        if (completedAfterTrigger) {
            Log.i(TAG, "Skipping slash for alarm ${alarm.id} - already completed")
            return
        }

        if (!transactionQueue.hasActive("SLASH", alarm.id)) {
            transactionQueue.enqueue(type = "SLASH", alarmId = alarm.id)
            Log.w(TAG, "Queued slash for alarm ${alarm.id}")
        }
    }

    /**
     * Update onchain PDA address after successful transaction.
     */
    suspend fun updateOnchainAddress(id: Long, pdaAddress: String) {
        val alarm = alarmDao.getById(id) ?: return
        alarmDao.update(alarm.copy(onchainPubkey = pdaAddress))
        Log.i(TAG, "Alarm $id onchain address updated: $pdaAddress")
    }

    /**
     * Update deposit status (e.g., if user skips signing).
     */
    suspend fun updateDepositStatus(id: Long, hasDeposit: Boolean) {
        val alarm = alarmDao.getById(id) ?: return
        val updated = if (hasDeposit) {
            alarm.copy(hasDeposit = true)
        } else {
            alarm.copy(
                hasDeposit = false,
                depositLamports = 0,
                onchainPubkey = null,
                onchainAlarmId = null,
                snoozeCount = 0
            )
        }
        alarmDao.update(updated)
        Log.i(TAG, "Alarm $id deposit status updated: $hasDeposit")
    }

    /**
     * Delete alarm after on-chain resolution (claim, slash, or refund).
     */
    suspend fun deleteResolvedAlarm(id: Long) {
        alarmScheduler.cancel(id)
        alarmDao.deleteById(id)
        Log.i(TAG, "Alarm $id deleted (on-chain resolved)")
    }

    /**
     * Record a successful onchain deposit for stats.
     */
    suspend fun recordDeposit(amountLamports: Long) {
        if (amountLamports <= 0) return
        ensureStatsExist()
        statsDao.addDeposit(amountLamports)
    }

    suspend fun queueCreateAlarm(alarmId: Long) {
        if (!transactionQueue.hasActive("CREATE_ALARM", alarmId)) {
            transactionQueue.enqueue(type = "CREATE_ALARM", alarmId = alarmId)
            PendingCreateNotificationWorker.enqueue(context, alarmId)
        }
    }

    /**
     * Record an emergency refund and update alarm state.
     */
    suspend fun recordEmergencyRefund(alarm: AlarmEntity) {
        if (alarm.depositLamports > 0) {
            ensureStatsExist()
            val penalty = alarm.depositLamports * app.solarma.wallet.OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
            val refund = alarm.depositLamports - penalty
            if (refund > 0) {
                statsDao.addSaved(refund)
            }
            if (penalty > 0) {
                statsDao.addSlashed(penalty)
            }
        }
        alarmDao.update(
            alarm.copy(
                hasDeposit = false,
                depositLamports = 0,
                depositAmount = 0.0
                // Keep onchainPubkey! Clearing it causes sync to re-insert
                // the alarm as a duplicate with hasDeposit=true.
            )
        )
    }

    /**
     * Import onchain alarms for a wallet address.
     */
    suspend fun importOnchainAlarms(ownerAddress: String): Result<ImportResult> {
        val programId = SolarmaInstructionBuilder.PROGRAM_ID.toBase58()
        val accountsResult = rpcClient.getProgramAccounts(programId, ownerAddress)
        return accountsResult.map { accounts ->
            var imported = 0
            var updated = 0
            var skipped = 0
            val now = System.currentTimeMillis()

            for (account in accounts) {
                val parsed = parseAlarm(account.pubkey, account.dataBase64)
                if (parsed == null) {
                    skipped++
                    continue
                }
                if (parsed.owner != ownerAddress) {
                    skipped++
                    continue
                }
                if (parsed.status != 0 || parsed.remainingAmount <= 0) {
                    skipped++
                    continue
                }

                val alarmTimeMillis = parsed.alarmTimeUnix * 1000
                val depositLamports = parsed.remainingAmount
                val depositSol = depositLamports / 1_000_000_000.0
                val isEnabled = alarmTimeMillis > now

                val existing = alarmDao.getByOnchainPubkey(parsed.pubkey)
                if (existing != null) {
                    val updatedAlarm = existing.copy(
                        alarmTimeMillis = alarmTimeMillis,
                        isEnabled = isEnabled,
                        hasDeposit = true,
                        depositAmount = depositSol,
                        depositLamports = depositLamports,
                        penaltyRoute = parsed.penaltyRoute,
                        penaltyDestination = parsed.penaltyDestination,
                        onchainPubkey = parsed.pubkey,
                        snoozeCount = parsed.snoozeCount
                    )
                    alarmDao.update(updatedAlarm)
                    if (updatedAlarm.isEnabled) {
                        alarmScheduler.schedule(updatedAlarm.id, alarmTimeMillis)
                    }
                    updated++
                } else {
                    val newAlarm = AlarmEntity(
                        alarmTimeMillis = alarmTimeMillis,
                        label = "Imported Alarm",
                        isEnabled = isEnabled,
                        repeatDays = 0,
                        wakeProofType = 1,
                        targetSteps = 20,
                        hasDeposit = true,
                        depositAmount = depositSol,
                        depositLamports = depositLamports,
                        penaltyRoute = parsed.penaltyRoute,
                        penaltyDestination = parsed.penaltyDestination,
                        onchainPubkey = parsed.pubkey,
                        onchainAlarmId = null,
                        snoozeCount = parsed.snoozeCount
                    )
                    val id = alarmDao.insert(newAlarm)
                    if (isEnabled) {
                        alarmScheduler.schedule(id, alarmTimeMillis)
                    }
                    imported++
                }
            }

            ImportResult(imported = imported, updated = updated, skipped = skipped)
        }
    }
}

data class ImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int
)
