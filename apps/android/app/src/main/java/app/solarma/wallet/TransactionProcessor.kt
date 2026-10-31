package app.solarma.wallet

import android.util.Log
import app.solarma.alarm.AlarmTiming
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.sol4k.PublicKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Processes pending transactions from the offline queue.
 *
 * The on-chain alarm account is the source of truth: before building anything the
 * processor reads it and decides what is still possible (ack + claim, claim, sweep,
 * snooze, slash, refund) from its status and on-chain deadline, not from local times.
 *
 * Every sent attempt is recorded with its signature and send-time block height. A
 * transaction is only re-signed once the previous attempt has failed or its blockhash
 * has expired, so a landed transaction is never repeated (e.g. a double snooze).
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
        private val deviceKeyStore: DeviceKeyStore,
    ) {
        companion object {
            private const val TAG = "Solarma.TxProcessor"
            private const val MAX_RETRIES = 5

            /** A blockhash stays valid for 150 blocks; after that the transaction can no longer land. */
            internal const val BLOCKHASH_VALIDITY_BLOCKS = 150L

            private const val CONFIRM_POLL_MS = 2_000L
            private const val CONFIRM_TIMEOUT_MS = 60_000L

            const val TYPE_CREATE_ALARM = "CREATE_ALARM"
            const val TYPE_ACK_AND_CLAIM = "ACK_AND_CLAIM"
            const val TYPE_SWEEP = "SWEEP"
            const val TYPE_SNOOZE = "SNOOZE"
            const val TYPE_SLASH = "SLASH"
            const val TYPE_EMERGENCY_REFUND = "EMERGENCY_REFUND"

            // Legacy types still present in queues created by older versions.
            const val TYPE_ACK_AWAKE = "ACK_AWAKE"
            const val TYPE_CLAIM = "CLAIM"
        }

        /** What to do with a queued transaction given the current on-chain state. */
        internal sealed class Action {
            object AckAndClaim : Action()

            object Claim : Action()

            object Sweep : Action()

            data class Snooze(val expectedSnoozeCount: Int) : Action()

            object Slash : Action()

            object Refund : Action()

            /** Not possible yet; try again later without consuming a retry. */
            data class Wait(val reason: String) : Action()

            /** No longer possible. */
            data class Fail(val reason: String) : Action()

            /** The alarm has already been resolved on-chain (account closed). */
            object Resolved : Action()
        }

        private val processingMutex = Mutex()

        /**
         * Background polling is intentionally disabled.
         * MWA requires an ActivityResultSender, so transactions can only be
         * processed via [processPendingTransactionsWithUi] from a foreground Activity.
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
                for (tx in pending) {
                    if (tx.retryCount >= MAX_RETRIES) {
                        markFailed(tx, "Max retries exceeded")
                        continue
                    }
                    try {
                        process(tx, activityResultSender)
                    } catch (e: Exception) {
                        Log.e(TAG, "Transaction ${tx.id} (${tx.type}) failed", e)
                        handleError(tx, e.message)
                    }
                }
            } finally {
                processingMutex.unlock()
            }
        }

        private suspend fun process(
            tx: PendingTransaction,
            activityResultSender: ActivityResultSender,
        ) {
            val alarm =
                tx.alarmId?.let { alarmDao.getById(it) } ?: run {
                    markFailed(tx, if (tx.alarmId == null) "Missing alarmId" else "Alarm not found")
                    return
                }
            val ownerAddress =
                walletManager.getConnectedWallet()
                    ?: throw IllegalStateException("Wallet not connected")
            val owner = PublicKey(ownerAddress)
            val alarmPda = resolveAlarmPda(alarm, owner)

            // 1. A previous attempt that can still land must not be signed again.
            val previous = tx.signature
            if (previous != null) {
                when (val outcome = checkAttempt(previous, tx.sentAtBlockHeight)) {
                    AttemptOutcome.Confirmed -> {
                        onConfirmed(tx, alarm, alarmPda, before = null)
                        return
                    }
                    is AttemptOutcome.Failed -> {
                        transactionDao.updateSent(tx.id, null, null)
                        handleError(tx, outcome.error)
                        return
                    }
                    AttemptOutcome.Pending -> {
                        markWaiting(tx, "Awaiting confirmation of $previous")
                        return
                    }
                    AttemptOutcome.Expired -> transactionDao.updateSent(tx.id, null, null)
                }
            }

            transactionDao.updateStatus(tx.id, "SENDING", null, System.currentTimeMillis())

            // 2. Decide from on-chain state what (if anything) to send.
            val before: OnchainAlarmAccount?
            val build: suspend () -> ByteArray
            if (tx.type == TYPE_CREATE_ALARM) {
                if (rpcClient.accountExists(alarmPda.toBase58()).getOrThrow()) {
                    Log.i(TAG, "Create alarm already confirmed onchain: ${tx.id}")
                    onConfirmed(tx, alarm, alarmPda, before = null)
                    return
                }
                if (alarm.depositLamports <= 0) {
                    markFailed(tx, "Missing deposit for create")
                    return
                }
                before = null
                build = { buildCreate(alarm, owner) }
            } else {
                val dataBase64 = rpcClient.getAccountDataBase64(alarmPda.toBase58()).getOrThrow()
                before = dataBase64?.let { OnchainAlarmParser.parse(alarmPda.toBase58(), it) }
                if (dataBase64 != null && before == null) {
                    markFailed(tx, "Unrecognized on-chain alarm account")
                    return
                }
                val nowSec = System.currentTimeMillis() / 1000
                when (val action = decide(tx.type, before, nowSec, ownerAddress)) {
                    Action.AckAndClaim -> build = { transactionBuilder.buildAckAndClaimTransactionByPubkey(owner, alarmPda) }
                    Action.Claim -> build = { transactionBuilder.buildClaimTransactionByPubkey(owner, alarmPda) }
                    Action.Sweep -> {
                        val alarmOwner = PublicKey(before!!.owner)
                        build = { transactionBuilder.buildSweepTransactionByPubkey(owner, alarmPda, alarmOwner) }
                    }
                    is Action.Snooze -> {
                        val expected = action.expectedSnoozeCount
                        build = { transactionBuilder.buildSnoozeTransactionByPubkey(owner, alarmPda, expected) }
                    }
                    Action.Slash -> {
                        val onchain = before!!
                        build = { buildSlash(owner, alarmPda, onchain) }
                    }
                    Action.Refund -> build = { transactionBuilder.buildEmergencyRefundTransactionByPubkey(owner, alarmPda) }
                    is Action.Wait -> {
                        markWaiting(tx, action.reason)
                        return
                    }
                    is Action.Fail -> {
                        markFailed(tx, action.reason)
                        return
                    }
                    Action.Resolved -> {
                        Log.i(TAG, "Alarm ${alarm.id} already resolved on-chain; dropping ${tx.type}")
                        resolveLocalAlarm(alarm)
                        transactionDao.updateStatus(tx.id, "CONFIRMED", "Already resolved on-chain", System.currentTimeMillis())
                        return
                    }
                }
            }

            // 3. Build inside the wallet session (fresh blockhash), sign, record, send.
            val signedTx = walletManager.signTransaction(activityResultSender, ownerAddress, build).getOrThrow()
            val signature = Base58.firstSignatureOf(signedTx)
            val sentAtHeight = rpcClient.getBlockHeight().getOrThrow()
            transactionDao.updateSent(tx.id, signature, sentAtHeight)

            val sendResult = rpcClient.sendTransactionFanOut(signedTx)
            if (sendResult.isFailure) {
                val error = sendResult.exceptionOrNull()?.message
                if (ErrorClassifier.programErrorCode(error) != null) {
                    // Rejected in preflight simulation: it was not forwarded and can't land.
                    transactionDao.updateSent(tx.id, null, null)
                    handleError(tx, error)
                } else {
                    // Unknown whether any node forwarded it; the signature is checked next round.
                    markWaiting(tx, "Send result unknown: $error")
                }
                return
            }

            // 4. Confirm.
            when (val outcome = awaitConfirmation(signature, sentAtHeight)) {
                AttemptOutcome.Confirmed -> onConfirmed(tx, alarm, alarmPda, before)
                is AttemptOutcome.Failed -> {
                    transactionDao.updateSent(tx.id, null, null)
                    val alreadyCreated =
                        tx.type == TYPE_CREATE_ALARM &&
                            rpcClient.accountExists(alarmPda.toBase58()).getOrNull() == true
                    if (alreadyCreated) {
                        // An earlier attempt created it ("already in use"): that's the outcome we wanted.
                        onConfirmed(tx, alarm, alarmPda, before)
                    } else {
                        handleError(tx, outcome.error)
                    }
                }
                AttemptOutcome.Pending -> markWaiting(tx, "Awaiting confirmation of $signature")
                AttemptOutcome.Expired -> {
                    transactionDao.updateSent(tx.id, null, null)
                    markRetry(tx, "Transaction expired before confirmation")
                }
            }
        }

        /**
         * Decide what a queued transaction should do given the on-chain alarm
         * ([onchain] = null when the account does not exist) at [nowSec].
         * Pure function: no I/O, unit-tested directly.
         */
        internal fun decide(
            type: String,
            onchain: OnchainAlarmAccount?,
            nowSec: Long,
            callerAddress: String,
        ): Action {
            if (onchain == null || onchain.status > OnchainParameters.STATUS_ACKNOWLEDGED) return Action.Resolved
            val created = onchain.status == OnchainParameters.STATUS_CREATED

            fun claimOrSweep(): Action =
                when {
                    created && nowSec < onchain.alarmTimeUnix -> Action.Wait("Alarm time not reached on-chain yet")
                    created && nowSec < onchain.deadlineUnix -> Action.AckAndClaim
                    created -> Action.Fail("Deadline passed — deposit can no longer be claimed")
                    nowSec <= onchain.deadlineUnix + OnchainParameters.CLAIM_GRACE_SECONDS -> Action.Claim
                    else -> Action.Sweep
                }

            return when (type) {
                TYPE_ACK_AND_CLAIM, TYPE_ACK_AWAKE, TYPE_CLAIM, TYPE_SWEEP -> claimOrSweep()
                TYPE_SNOOZE ->
                    when {
                        !created -> Action.Fail("Alarm already acknowledged — snooze not possible")
                        nowSec < onchain.alarmTimeUnix -> Action.Wait("Alarm time not reached on-chain yet")
                        nowSec >= onchain.deadlineUnix -> Action.Fail("Deadline passed — snooze not possible")
                        onchain.snoozeCount >= OnchainParameters.MAX_SNOOZE_COUNT -> Action.Fail("Maximum snoozes reached")
                        else -> Action.Snooze(onchain.snoozeCount)
                    }
                TYPE_SLASH ->
                    when {
                        // The owner acknowledged after all: the deposit goes back, not to the penalty.
                        !created -> claimOrSweep()
                        nowSec < onchain.deadlineUnix -> Action.Wait("Deadline not passed")
                        else -> Action.Slash
                    }
                TYPE_EMERGENCY_REFUND ->
                    when {
                        !created -> Action.Fail("Alarm already acknowledged — refund not possible")
                        nowSec < onchain.alarmTimeUnix -> Action.Refund
                        else -> Action.Fail("Too late for refund — alarm time has passed")
                    }
                else -> Action.Fail("Unknown transaction type $type")
            }
        }

        private suspend fun buildSlash(
            caller: PublicKey,
            alarmPda: PublicKey,
            onchain: OnchainAlarmAccount,
        ): ByteArray =
            transactionBuilder.buildSlashTransactionByPubkey(
                owner = caller,
                alarmPda = alarmPda,
                penaltyRoute = onchain.penaltyRoute,
                penaltyDestination = onchain.penaltyDestination,
                alarmOwner = PublicKey(onchain.owner),
            )

        private suspend fun buildCreate(
            alarm: AlarmEntity,
            owner: PublicKey,
        ): ByteArray {
            val alarmTimeUnix = alarm.alarmTimeMillis / 1000
            val deadlineUnix = alarm.onchainDeadline ?: (alarmTimeUnix + AlarmTiming.GRACE_PERIOD_SECONDS)
            val route = PenaltyRoute.fromCode(alarm.penaltyRoute)
            val buddyAddress = if (route == PenaltyRoute.BUDDY) alarm.penaltyDestination else null
            return transactionBuilder.buildCreateAlarmTransaction(
                owner = owner,
                alarmId = alarm.onchainAlarmId ?: alarm.id,
                alarmTimeUnix = alarmTimeUnix,
                deadlineUnix = deadlineUnix,
                depositLamports = alarm.depositLamports,
                penaltyRoute = route,
                buddyAddress = buddyAddress?.let { PublicKey(it) },
                // Reuse an existing device key; funding it is left to the interactive create flow
                ackAuthority = deviceKeyStore.existing()?.publicKey,
            )
        }

        internal sealed class AttemptOutcome {
            object Confirmed : AttemptOutcome()

            data class Failed(val error: String) : AttemptOutcome()

            /** Not seen yet but may still land. */
            object Pending : AttemptOutcome()

            /** Not seen and its blockhash has expired: it can never land. */
            object Expired : AttemptOutcome()
        }

        /** Single status check of a previously sent attempt. */
        private suspend fun checkAttempt(
            signature: String,
            sentAtHeight: Long?,
        ): AttemptOutcome {
            val status = rpcClient.getSignatureStatus(signature).getOrThrow()
            status.err?.let { return AttemptOutcome.Failed(it) }
            if (status.confirmationStatus == "confirmed" || status.confirmationStatus == "finalized") {
                return AttemptOutcome.Confirmed
            }
            val height = rpcClient.getBlockHeight().getOrThrow()
            return if (sentAtHeight == null || height > sentAtHeight + BLOCKHASH_VALIDITY_BLOCKS) {
                AttemptOutcome.Expired
            } else {
                AttemptOutcome.Pending
            }
        }

        /** Poll a just-sent transaction until it confirms, fails, expires, or the timeout passes. */
        private suspend fun awaitConfirmation(
            signature: String,
            sentAtHeight: Long,
        ): AttemptOutcome {
            val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
            while (true) {
                val outcome =
                    try {
                        checkAttempt(signature, sentAtHeight)
                    } catch (e: Exception) {
                        Log.w(TAG, "Confirmation check failed (will retry)", e)
                        AttemptOutcome.Pending
                    }
                if (outcome != AttemptOutcome.Pending || System.currentTimeMillis() >= deadline) return outcome
                delay(CONFIRM_POLL_MS)
            }
        }

        /** Apply local effects of a confirmed transaction, refreshing from the chain. */
        private suspend fun onConfirmed(
            tx: PendingTransaction,
            alarm: AlarmEntity,
            alarmPda: PublicKey,
            before: OnchainAlarmAccount?,
        ) {
            ensureStatsRow()
            val afterData = rpcClient.getAccountDataBase64(alarmPda.toBase58()).getOrNull()
            val after = afterData?.let { OnchainAlarmParser.parse(alarmPda.toBase58(), it) }
            val remainingBefore = before?.remainingAmount ?: alarm.depositLamports

            when (tx.type) {
                TYPE_CREATE_ALARM -> {
                    if (alarm.depositLamports > 0) statsDao.addDeposit(alarm.depositLamports)
                    alarmDao.update(
                        alarm.copy(
                            onchainPubkey = alarmPda.toBase58(),
                            hasDeposit = true,
                            onchainDeadline = after?.deadlineUnix ?: alarm.onchainDeadline,
                        ),
                    )
                }
                TYPE_SNOOZE -> {
                    // Snooze presses are counted by AlarmRepository.snooze; only the cost is recorded here.
                    if (after != null) {
                        val cost = (remainingBefore - after.remainingAmount).coerceAtLeast(0)
                        if (cost > 0) statsDao.addSlashed(cost)
                        alarmDao.update(
                            alarm.copy(
                                depositLamports = after.remainingAmount,
                                snoozeCount = after.snoozeCount,
                                onchainDeadline = after.deadlineUnix,
                            ),
                        )
                    }
                }
                TYPE_SLASH -> {
                    if (after == null) {
                        if (remainingBefore > 0) statsDao.addSlashed(remainingBefore)
                        resolveLocalAlarm(alarm)
                    } else {
                        // SLASH turned into claim/sweep because the alarm was acknowledged
                        if (remainingBefore > 0) statsDao.addSaved(remainingBefore)
                        resolveLocalAlarm(alarm)
                    }
                }
                TYPE_EMERGENCY_REFUND -> {
                    if (remainingBefore > 0) {
                        val penalty = remainingBefore * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
                        if (remainingBefore - penalty > 0) statsDao.addSaved(remainingBefore - penalty)
                        if (penalty > 0) statsDao.addSlashed(penalty)
                    }
                    resolveLocalAlarm(alarm)
                }
                else -> {
                    // ACK_AND_CLAIM / CLAIM / ACK_AWAKE / SWEEP: deposit returned to the owner
                    if (remainingBefore > 0) statsDao.addSaved(remainingBefore)
                    resolveLocalAlarm(alarm)
                }
            }

            transactionDao.updateStatus(tx.id, "CONFIRMED", null, System.currentTimeMillis())
            Log.i(TAG, "Transaction ${tx.id} (${tx.type}) confirmed")
        }

        private suspend fun resolveLocalAlarm(alarm: AlarmEntity) = alarmDao.resolveDeposit(alarm)

        private suspend fun handleError(
            tx: PendingTransaction,
            error: String?,
        ) {
            when (ErrorClassifier.classify(error)) {
                ErrorClassifier.Category.NON_RETRYABLE -> markFailed(tx, error ?: "Transaction failed")
                ErrorClassifier.Category.RETRY_LATER -> markWaiting(tx, error ?: "Not yet possible")
                ErrorClassifier.Category.RETRYABLE -> markRetry(tx, error ?: "Transaction failed")
            }
        }

        private suspend fun markFailed(
            tx: PendingTransaction,
            reason: String,
        ) {
            transactionDao.updateStatus(tx.id, "FAILED", reason, System.currentTimeMillis())
            Log.w(TAG, "Transaction ${tx.id} (${tx.type}) failed: $reason")
        }

        /** Keep pending without consuming a retry attempt. */
        private suspend fun markWaiting(
            tx: PendingTransaction,
            reason: String,
        ) {
            transactionDao.updateStatus(tx.id, "PENDING", reason, System.currentTimeMillis())
            Log.i(TAG, "Transaction ${tx.id} (${tx.type}) waiting: $reason")
        }

        private suspend fun markRetry(
            tx: PendingTransaction,
            reason: String,
        ) {
            transactionDao.updateStatus(tx.id, "PENDING", reason, System.currentTimeMillis())
            transactionDao.incrementRetry(tx.id)
            Log.w(TAG, "Transaction ${tx.id} (${tx.type}) will retry: $reason")
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
            val deadlineMillis = alarmTimeMillis + AlarmTiming.GRACE_PERIOD_MILLIS
            return nowMillis >= deadlineMillis
        }

        private fun resolveAlarmPda(
            alarm: AlarmEntity,
            owner: PublicKey,
        ): PublicKey {
            val onchainPubkey = alarm.onchainPubkey
            if (!onchainPubkey.isNullOrBlank()) {
                return PublicKey(onchainPubkey)
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
