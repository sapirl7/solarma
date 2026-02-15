package app.solarma.wallet

import android.util.Log
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Processes pending transactions from the offline queue.
 * Retries failed transactions with exponential backoff.
 */
@Singleton
class TransactionProcessor
    @Inject
    constructor(
        private val networkChecker: NetworkChecker,
        private val transactionDao: PendingTransactionDao,
        private val walletManager: WalletManager,
        private val transactionBuilder: TransactionBuilder,
        private val rpcClient: SolanaRpcClient,
        private val alarmDao: AlarmDao,
        private val statsDao: StatsDao,
    ) {
        companion object {
            private const val TAG = "Solarma.TxProcessor"
            private const val MAX_RETRIES = 5
            private const val BASE_DELAY_MS = 1000L
        }

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val processingMutex = Mutex()

        /**
         * Background polling is intentionally disabled.
         * MWA requires an ActivityResultSender, so transactions can only be
         * processed via [processPendingTransactionsWithUi] from a foreground Activity.
         * The previous polling loop called a no-op every 10s, wasting CPU.
         */
        fun start() {
            Log.d(TAG, "TransactionProcessor.start() — no-op, use processPendingTransactionsWithUi()")
        }

        fun stop() {
            Log.d(TAG, "TransactionProcessor.stop() — no-op")
        }

        /**
         * Process pending transactions with UI available for wallet signing.
         * Call this from a foreground Activity (e.g., MainActivity onResume).
         */
        suspend fun processPendingTransactionsWithUi(activityResultSender: ActivityResultSender) {
            if (!processingMutex.tryLock()) return
            try {
                if (!networkChecker.isNetworkAvailable()) {
                    Log.d(TAG, "No network, skipping pending transactions")
                    return
                }
                if (!walletManager.isConnected()) {
                    Log.d(TAG, "Wallet not connected, skipping pending transactions")
                    return
                }
                val pending = transactionDao.getPendingTransactions().first()
                if (pending.isEmpty()) return

                for (tx in pending) {
                    if (tx.retryCount >= MAX_RETRIES) {
                        transactionDao.updateStatus(tx.id, "FAILED", "Max retries exceeded", System.currentTimeMillis())
                        Log.w(TAG, "Transaction ${tx.id} failed after max retries")
                        continue
                    }

                    try {
                        transactionDao.updateStatus(tx.id, "SENDING", null, System.currentTimeMillis())
                        val alarmId = tx.alarmId
                        if (alarmId == null) {
                            transactionDao.updateStatus(tx.id, "FAILED", "Missing alarmId", System.currentTimeMillis())
                            Log.w(TAG, "Transaction ${tx.id} missing alarmId")
                            continue
                        }

                        val alarm = alarmDao.getById(alarmId)
                        if (alarm == null) {
                            transactionDao.updateStatus(tx.id, "FAILED", "Alarm not found", System.currentTimeMillis())
                            Log.w(TAG, "Transaction ${tx.id} alarm not found")
                            continue
                        }

                        val ownerAddress =
                            walletManager.getConnectedWallet()
                                ?: throw IllegalStateException("Wallet not connected")
                        val owner = org.sol4k.PublicKey(ownerAddress)
                        val alarmPda = resolveAlarmPda(alarm, owner)

                        val txBytes =
                            when (tx.type) {
                                "CREATE_ALARM" -> {
                                    val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                                    val alarmPda =
                                        transactionBuilder.instructionBuilder
                                            .deriveAlarmPda(owner, createAlarmId)
                                            .address
                                            .toBase58()

                                    val exists = rpcClient.accountExists(alarmPda).getOrNull() == true
                                    if (exists) {
                                        alarmDao.update(
                                            alarm.copy(
                                                onchainPubkey = alarmPda,
                                                hasDeposit = true,
                                            ),
                                        )
                                        ensureStatsRow()
                                        if (alarm.depositLamports > 0) {
                                            statsDao.addDeposit(alarm.depositLamports)
                                        }
                                        transactionDao.updateStatus(tx.id, "CONFIRMED", null, System.currentTimeMillis())
                                        Log.i(TAG, "Create alarm already confirmed onchain: ${tx.id}")
                                        continue
                                    }

                                    if (alarm.depositLamports <= 0) {
                                        transactionDao.updateStatus(
                                            tx.id,
                                            "FAILED",
                                            "Missing deposit for create",
                                            System.currentTimeMillis(),
                                        )
                                        Log.w(TAG, "Create alarm missing deposit for alarm ${alarm.id}")
                                        continue
                                    }

                                    val alarmTimeUnix = alarm.alarmTimeMillis / 1000
                                    val deadlineUnix = alarmTimeUnix + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_SECONDS
                                    val route = PenaltyRoute.fromCode(alarm.penaltyRoute)
                                    val buddyAddress = if (route == PenaltyRoute.BUDDY) alarm.penaltyDestination else null

                                    transactionBuilder.buildCreateAlarmTransaction(
                                        owner = owner,
                                        alarmId = createAlarmId,
                                        alarmTimeUnix = alarmTimeUnix,
                                        deadlineUnix = deadlineUnix,
                                        depositLamports = alarm.depositLamports,
                                        penaltyRoute = route,
                                        buddyAddress = buddyAddress?.let { org.sol4k.PublicKey(it) },
                                    )
                                }
                                "CLAIM" -> {
                                    if (isDeadlinePassed(alarm.alarmTimeMillis, System.currentTimeMillis())) {
                                        transactionDao.updateStatus(
                                            tx.id, "FAILED",
                                            "Deadline passed — claim no longer possible",
                                            System.currentTimeMillis(),
                                        )
                                        Log.w(TAG, "Claim skipped: deadline passed for alarm ${alarm.id}")
                                        continue
                                    }
                                    transactionBuilder.buildClaimTransactionByPubkey(owner, alarmPda)
                                }
                                "ACK_AWAKE" -> {
                                    if (isDeadlinePassed(alarm.alarmTimeMillis, System.currentTimeMillis())) {
                                        transactionDao.updateStatus(
                                            tx.id, "FAILED",
                                            "Deadline passed — ack no longer possible",
                                            System.currentTimeMillis(),
                                        )
                                        Log.w(TAG, "AckAwake skipped: deadline passed for alarm ${alarm.id}")
                                        continue
                                    }
                                    transactionBuilder.buildAckAwakeTransactionByPubkey(owner, alarmPda)
                                }
                                "SNOOZE" -> transactionBuilder.buildSnoozeTransactionByPubkey(owner, alarmPda, alarm.snoozeCount)
                                "SLASH" -> {
                                    val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                    if (System.currentTimeMillis() < deadlineMillis) {
                                        transactionDao.updateStatus(
                                            tx.id,
                                            "PENDING",
                                            "Deadline not passed",
                                            System.currentTimeMillis(),
                                        )
                                        Log.d(TAG, "Slash skipped until deadline for alarm ${alarm.id}")
                                        continue
                                    }
                                    transactionBuilder.buildSlashTransactionByPubkey(
                                        owner = owner,
                                        alarmPda = alarmPda,
                                        penaltyRoute = alarm.penaltyRoute,
                                        penaltyDestination = alarm.penaltyDestination,
                                    )
                                }
                                "EMERGENCY_REFUND" ->
                                    transactionBuilder.buildEmergencyRefundTransactionByPubkey(
                                        owner = owner,
                                        alarmPda = alarmPda,
                                    )
                                else -> {
                                    transactionDao.updateStatus(tx.id, "FAILED", "Unknown type ${tx.type}", System.currentTimeMillis())
                                    Log.w(TAG, "Unknown transaction type: ${tx.type}")
                                    continue
                                }
                            }

                        // Sign via MWA, then fan-out to all RPCs for max landing probability
                        val signResult = walletManager.signTransaction(activityResultSender, txBytes)
                        if (signResult.isFailure) {
                            val e = signResult.exceptionOrNull()
                            transactionDao.updateStatus(tx.id, "PENDING", e?.message, System.currentTimeMillis())
                            transactionDao.incrementRetry(tx.id)
                            Log.e(TAG, "Transaction ${tx.id} signing failed", e)
                            continue
                        }
                        val signedTx = signResult.getOrThrow()

                        val sendResult = rpcClient.sendTransactionFanOut(signedTx)
                        if (sendResult.isFailure) {
                            val e = sendResult.exceptionOrNull()
                            transactionDao.updateStatus(tx.id, "PENDING", e?.message, System.currentTimeMillis())
                            transactionDao.incrementRetry(tx.id)
                            Log.e(TAG, "Transaction ${tx.id} fan-out send failed", e)
                            continue
                        }
                        val signature = sendResult.getOrThrow()

                        val status = rpcClient.getSignatureStatus(signature).getOrNull()
                        if (status != null && status.err != null) {
                            if (tx.type == "CREATE_ALARM") {
                                val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                                val alarmPda =
                                    transactionBuilder.instructionBuilder
                                        .deriveAlarmPda(owner, createAlarmId)
                                        .address
                                        .toBase58()
                                val exists = rpcClient.accountExists(alarmPda).getOrNull() == true
                                if (exists) {
                                    alarmDao.update(
                                        alarm.copy(
                                            onchainPubkey = alarmPda,
                                            hasDeposit = true,
                                        ),
                                    )
                                    ensureStatsRow()
                                    if (alarm.depositLamports > 0) {
                                        statsDao.addDeposit(alarm.depositLamports)
                                    }
                                    transactionDao.updateStatus(tx.id, "CONFIRMED", null, System.currentTimeMillis())
                                    Log.i(TAG, "Create alarm confirmed despite error: ${status.err}")
                                    continue
                                }
                            }
                            val errCategory = ErrorClassifier.classify(status.err)
                            if (errCategory == ErrorClassifier.Category.NON_RETRYABLE) {
                                transactionDao.updateStatus(tx.id, "FAILED", status.err, System.currentTimeMillis())
                                Log.w(TAG, "Transaction ${tx.id} permanently failed: ${status.err}")
                            } else {
                                transactionDao.updateStatus(tx.id, "PENDING", status.err, System.currentTimeMillis())
                                transactionDao.incrementRetry(tx.id)
                                Log.w(TAG, "Transaction ${tx.id} status.err retryable: ${status.err}")
                            }
                            continue
                        }
                        val confirmed =
                            status?.confirmationStatus == "confirmed" ||
                                status?.confirmationStatus == "finalized"
                        if (!confirmed) {
                            transactionDao.updateStatus(tx.id, "PENDING", "Not confirmed", System.currentTimeMillis())
                            transactionDao.incrementRetry(tx.id)
                            Log.w(TAG, "Transaction ${tx.id} not confirmed yet")
                            continue
                        }

                        ensureStatsRow()

                        when (tx.type) {
                            "CREATE_ALARM" -> {
                                val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                                val alarmPda =
                                    transactionBuilder.instructionBuilder
                                        .deriveAlarmPda(owner, createAlarmId)
                                        .address
                                        .toBase58()
                                alarmDao.update(
                                    alarm.copy(
                                        onchainPubkey = alarmPda,
                                        hasDeposit = true,
                                    ),
                                )
                                if (alarm.depositLamports > 0) {
                                    statsDao.addDeposit(alarm.depositLamports)
                                }
                            }
                            "CLAIM" -> {
                                if (alarm.depositLamports > 0) {
                                    statsDao.addSaved(alarm.depositLamports)
                                }
                                // Alarm fully resolved — delete from DB
                                alarmDao.deleteById(alarm.id)
                                Log.i(TAG, "Alarm ${alarm.id} deleted after successful claim")
                            }
                            "ACK_AWAKE" -> {
                                // H3: Proof recorded on-chain. Claim will follow.
                                Log.i(TAG, "Alarm ${alarm.id} wake proof acknowledged on-chain")
                            }
                            "SLASH" -> {
                                if (alarm.depositLamports > 0) {
                                    statsDao.addSlashed(alarm.depositLamports)
                                }
                                // Alarm fully resolved — delete from DB
                                alarmDao.deleteById(alarm.id)
                                Log.i(TAG, "Alarm ${alarm.id} deleted after slash")
                            }
                            "EMERGENCY_REFUND" -> {
                                if (alarm.depositLamports > 0) {
                                    val penalty = alarm.depositLamports * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
                                    val refund = alarm.depositLamports - penalty
                                    if (refund > 0) {
                                        statsDao.addSaved(refund)
                                    }
                                    if (penalty > 0) {
                                        statsDao.addSlashed(penalty)
                                    }
                                }
                                // Alarm fully resolved — delete from DB
                                alarmDao.deleteById(alarm.id)
                                Log.i(TAG, "Alarm ${alarm.id} deleted after emergency refund")
                            }
                            "SNOOZE" -> {
                                val cost = computeSnoozeCost(alarm.depositLamports, alarm.snoozeCount)
                                if (cost > 0) {
                                    statsDao.addSlashed(cost)
                                }
                                statsDao.incrementSnoozes()
                                val newRemaining = (alarm.depositLamports - cost).coerceAtLeast(0)
                                alarmDao.update(
                                    alarm.copy(
                                        depositLamports = newRemaining,
                                        snoozeCount = alarm.snoozeCount + 1,
                                    ),
                                )
                            }
                        }

                        transactionDao.updateStatus(tx.id, "CONFIRMED", null, System.currentTimeMillis())
                        Log.i(TAG, "Transaction ${tx.id} confirmed")
                    } catch (e: Exception) {
                        val category = ErrorClassifier.classify(e.message)
                        if (category == ErrorClassifier.Category.NON_RETRYABLE) {
                            transactionDao.updateStatus(tx.id, "FAILED", e.message, System.currentTimeMillis())
                            Log.w(TAG, "Transaction ${tx.id} permanently failed: ${e.message}")
                        } else {
                            val delayMs = BASE_DELAY_MS * (1 shl tx.retryCount.coerceAtMost(5))
                            transactionDao.updateStatus(tx.id, "PENDING", e.message, System.currentTimeMillis())
                            transactionDao.incrementRetry(tx.id)
                            Log.e(TAG, "Transaction ${tx.id} failed, retry in ${delayMs}ms", e)
                        }
                    }
                }
            } finally {
                processingMutex.unlock()
            }
        }

        internal fun computeSnoozeCost(
            remaining: Long,
            snoozeCount: Int,
        ): Long {
            if (remaining <= 0) return 0
            val baseCost = remaining * OnchainParameters.SNOOZE_BASE_PERCENT / 100
            if (baseCost <= 0) return 0
            val safeCount = snoozeCount.coerceIn(0, 30)
            val multiplier = 1L shl safeCount
            // Guard against overflow: if baseCost * multiplier exceeds Long range,
            // the result wraps negative/garbage. Cap at remaining instead.
            val cost =
                if (multiplier > 0 && baseCost > Long.MAX_VALUE / multiplier) {
                    remaining // overflow → cap at total remaining
                } else {
                    baseCost * multiplier
                }
            return min(cost, remaining)
        }

        /**
         * Check if the alarm's deadline has passed.
         * deadline = alarmTimeMillis + GRACE_PERIOD_MILLIS
         */
        internal fun isDeadlinePassed(
            alarmTimeMillis: Long,
            nowMillis: Long,
        ): Boolean {
            val deadlineMillis = alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
            return nowMillis >= deadlineMillis
        }

        private fun resolveAlarmPda(
            alarm: app.solarma.data.local.AlarmEntity,
            owner: org.sol4k.PublicKey,
        ): org.sol4k.PublicKey {
            val onchainPubkey = alarm.onchainPubkey
            if (!onchainPubkey.isNullOrBlank()) {
                return org.sol4k.PublicKey(onchainPubkey)
            }
            val alarmId = alarm.onchainAlarmId ?: alarm.id
            return transactionBuilder.instructionBuilder
                .deriveAlarmPda(owner, alarmId)
                .address
        }

        private suspend fun ensureStatsRow() {
            if (statsDao.getStatsOnce() == null) {
                statsDao.insert(StatsEntity())
            }
        }
    }
