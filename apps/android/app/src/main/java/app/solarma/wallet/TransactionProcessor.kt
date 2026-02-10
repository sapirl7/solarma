package app.solarma.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.StatsEntity
import app.solarma.data.local.StatsDao
import app.solarma.wakeproof.WakeProofEngine
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlin.math.min
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes pending transactions from the offline queue.
 * Retries failed transactions with exponential backoff.
 */
@Singleton
class TransactionProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionDao: PendingTransactionDao,
    private val walletManager: WalletManager,
    private val transactionBuilder: TransactionBuilder,
    private val rpcClient: SolanaRpcClient,
    private val attestationClient: AttestationClient,
    private val alarmDao: AlarmDao,
    private val statsDao: StatsDao
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
            if (!isNetworkAvailable()) {
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
                var cachedAttestedSigBytes: ByteArray? = null
                var cachedAttestedExpTs: Long? = null
                var cachedAttestedNonce: Long? = null
                var cachedAttestedProofType: Int? = null
                var cachedAttestedProofHash: ByteArray? = null

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

                    val ownerAddress = walletManager.getConnectedWallet()
                        ?: throw IllegalStateException("Wallet not connected")
                    val owner = org.sol4k.PublicKey(ownerAddress)
                    val alarmPda = resolveAlarmPda(alarm, owner)

                    // If we already have a last signature, try to confirm it first.
                    val existingSig = tx.lastSignature
                    if (!existingSig.isNullOrBlank()) {
                        val status = rpcClient.getSignatureStatus(existingSig).getOrNull()
                        if (status?.err != null) {
                            transactionDao.updateStatusWithSignature(
                                tx.id,
                                "FAILED",
                                status.err,
                                System.currentTimeMillis(),
                                existingSig
                            )
                            Log.w(TAG, "Transaction ${tx.id} failed: ${status.err}")
                            continue
                        }
                        val confirmed = status?.confirmationStatus == "confirmed" ||
                            status?.confirmationStatus == "finalized"
                        if (confirmed) {
                            ensureStatsRow()
                            applyConfirmedSideEffects(tx, alarm, owner)
                            transactionDao.updateStatusWithSignature(
                                tx.id,
                                "CONFIRMED",
                                null,
                                System.currentTimeMillis(),
                                existingSig
                            )
                            Log.i(TAG, "Transaction ${tx.id} confirmed via existing signature")
                            continue
                        }
                    }

                    suspend fun buildUnsignedTxOrSkip(): ByteArray? {
                        val nowMillis = System.currentTimeMillis()
                        return when (tx.type) {
                            "CREATE_ALARM" -> {
                                val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                                val derivedAlarmPda = transactionBuilder.instructionBuilder
                                    .deriveAlarmPda(owner, createAlarmId)
                                    .address
                                    .toBase58()

                                val exists = rpcClient.accountExists(derivedAlarmPda).getOrNull() == true
                                if (exists) {
                                    alarmDao.update(
                                        alarm.copy(
                                            onchainPubkey = derivedAlarmPda,
                                            hasDeposit = true
                                        )
                                    )
                                    ensureStatsRow()
                                    if (alarm.depositLamports > 0) {
                                        statsDao.addDeposit(alarm.depositLamports)
                                    }
                                    transactionDao.updateStatusWithSignature(
                                        tx.id,
                                        "CONFIRMED",
                                        null,
                                        nowMillis,
                                        tx.lastSignature
                                    )
                                    Log.i(TAG, "Create alarm already confirmed onchain: ${tx.id}")
                                    return null
                                }

                                if (alarm.depositLamports <= 0) {
                                    transactionDao.updateStatus(tx.id, "FAILED", "Missing deposit for create", nowMillis)
                                    Log.w(TAG, "Create alarm missing deposit for alarm ${alarm.id}")
                                    return null
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
                                    buddyAddress = buddyAddress?.let { org.sol4k.PublicKey(it) }
                                )
                            }

                            "ACK_AWAKE" -> {
                                val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                if (nowMillis < alarm.alarmTimeMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "PENDING",
                                        "Too early to ACK",
                                        nowMillis
                                    )
                                    Log.d(TAG, "ACK_AWAKE skipped until alarm time for alarm ${alarm.id}")
                                    return null
                                }
                                if (nowMillis >= deadlineMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "FAILED",
                                        "ACK window expired",
                                        nowMillis
                                    )
                                    Log.w(TAG, "ACK_AWAKE missed deadline for alarm ${alarm.id}")
                                    return null
                                }
                                transactionBuilder.buildAckAwakeTransactionByPubkey(owner, alarmPda)
                            }

                            "ACK_AWAKE_ATTESTED" -> {
                                val deadlineMillis =
                                    alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                if (nowMillis < alarm.alarmTimeMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "PENDING",
                                        "Too early to ACK",
                                        nowMillis
                                    )
                                    Log.d(TAG, "ACK_AWAKE_ATTESTED skipped until alarm time for alarm ${alarm.id}")
                                    return null
                                }
                                if (nowMillis >= deadlineMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "FAILED",
                                        "ACK window expired",
                                        nowMillis
                                    )
                                    Log.w(TAG, "ACK_AWAKE_ATTESTED missed deadline for alarm ${alarm.id}")
                                    return null
                                }
                                if (app.solarma.BuildConfig.SOLARMA_ATTESTATION_SERVER_URL.isBlank()) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "FAILED",
                                        "Attestation server not configured",
                                        nowMillis
                                    )
                                    return null
                                }

                                val proofType = alarm.wakeProofType
                                val proofHash = computeProofHashForAttestation(alarm)
                                if (proofHash == null) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "FAILED",
                                        "Missing wake proof data for attestation",
                                        nowMillis
                                    )
                                    return null
                                }

                                val nonce = tx.createdAt
                                val expTs = cachedAttestedExpTs ?: ((System.currentTimeMillis() / 1000L) + 120L)

                                if (cachedAttestedSigBytes == null ||
                                    cachedAttestedNonce != nonce ||
                                    cachedAttestedExpTs != expTs ||
                                    cachedAttestedProofType != proofType ||
                                    cachedAttestedProofHash?.contentEquals(proofHash) != true
                                ) {
                                    val proofHashHex = bytesToHexLower(proofHash)
                                    val sigBase58 = attestationClient.requestAckPermit(
                                        cluster = PermitMessage.DEFAULT_CLUSTER,
                                        programId = SolarmaInstructionBuilder.PROGRAM_ID.toBase58(),
                                        alarmPda = alarmPda.toBase58(),
                                        owner = owner.toBase58(),
                                        nonce = nonce,
                                        expTs = expTs,
                                        proofType = proofType,
                                        proofHashHex = proofHashHex
                                    ).getOrThrow()

                                    val sigBytes = decodeBase58ToBytes(sigBase58)
                                    if (sigBytes.size != 64) {
                                        transactionDao.updateStatus(
                                            tx.id,
                                            "FAILED",
                                            "Invalid attestation signature length",
                                            nowMillis
                                        )
                                        return null
                                    }

                                    cachedAttestedSigBytes = sigBytes
                                    cachedAttestedNonce = nonce
                                    cachedAttestedExpTs = expTs
                                    cachedAttestedProofType = proofType
                                    cachedAttestedProofHash = proofHash
                                }

                                transactionBuilder.buildAckAwakeAttestedTransactionByPubkey(
                                    owner = owner,
                                    alarmPda = alarmPda,
                                    nonce = nonce,
                                    expTs = expTs,
                                    proofType = proofType,
                                    proofHash = proofHash,
                                    permitSignature = cachedAttestedSigBytes!!
                                )
                            }

                            "CLAIM" -> {
                                val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                val claimUntilMillis =
                                    deadlineMillis + (OnchainParameters.CLAIM_GRACE_SECONDS * 1000L)
                                if (nowMillis > claimUntilMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "FAILED",
                                        "Claim grace expired; use sweep",
                                        nowMillis
                                    )
                                    Log.w(TAG, "CLAIM grace expired for alarm ${alarm.id}")
                                    return null
                                }
                                transactionBuilder.buildClaimTransactionByPubkey(owner, alarmPda)
                            }

                            "SNOOZE" ->
                                transactionBuilder.buildSnoozeTransactionByPubkey(owner, alarmPda, alarm.snoozeCount)

                            "SLASH" -> {
                                val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                if (nowMillis < deadlineMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "PENDING",
                                        "Deadline not passed",
                                        nowMillis
                                    )
                                    Log.d(TAG, "Slash skipped until deadline for alarm ${alarm.id}")
                                    return null
                                }
                                transactionBuilder.buildSlashTransactionByPubkey(
                                    owner = owner,
                                    alarmPda = alarmPda,
                                    penaltyRoute = alarm.penaltyRoute,
                                    penaltyDestination = alarm.penaltyDestination
                                )
                            }

                            "SWEEP_ACKNOWLEDGED" -> {
                                val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                                val claimUntilMillis =
                                    deadlineMillis + (OnchainParameters.CLAIM_GRACE_SECONDS * 1000L)
                                if (nowMillis <= claimUntilMillis) {
                                    transactionDao.updateStatus(
                                        tx.id,
                                        "PENDING",
                                        "Claim grace not expired",
                                        nowMillis
                                    )
                                    Log.d(TAG, "Sweep skipped until grace expires for alarm ${alarm.id}")
                                    return null
                                }
                                transactionBuilder.buildSweepAcknowledgedTransactionByPubkey(owner, alarmPda)
                            }

                            "EMERGENCY_REFUND" -> transactionBuilder.buildEmergencyRefundTransactionByPubkey(
                                owner = owner,
                                alarmPda = alarmPda
                            )

                            else -> {
                                transactionDao.updateStatus(tx.id, "FAILED", "Unknown type ${tx.type}", nowMillis)
                                Log.w(TAG, "Unknown transaction type: ${tx.type}")
                                return null
                            }
                        }
                    }

                    val txBytes = buildUnsignedTxOrSkip() ?: continue

                    fun signatureBase58FromSignedTx(signedTx: ByteArray): String {
                        // Transaction format: [sig_count] + [sig1:64] + [message...]
                        if (signedTx.size < 1 + 64) return ""
                        val sigBytes = signedTx.copyOfRange(1, 1 + 64)
                        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
                        var num = BigInteger(1, sigBytes)
                        val sb = StringBuilder()
                        while (num > BigInteger.ZERO) {
                            val (q, r) = num.divideAndRemainder(BigInteger.valueOf(58))
                            sb.insert(0, alphabet[r.toInt()])
                            num = q
                        }
                        for (b in sigBytes) {
                            if (b.toInt() == 0) sb.insert(0, '1') else break
                        }
                        return sb.toString()
                    }

                    fun isRetryableError(msg: String?): Boolean {
                        val m = msg?.lowercase() ?: return false
                        return m.contains("timeout") ||
                            m.contains("timed out") ||
                            m.contains("all rpc endpoints failed") ||
                            m.contains("node is behind") ||
                            m.contains("blockhash not found") ||
                            m.contains("blockhash") && m.contains("not found")
                    }

                    fun isBlockhashError(msg: String?): Boolean {
                        val m = msg?.lowercase() ?: return false
                        return m.contains("blockhash not found") ||
                            (m.contains("blockhash") && m.contains("not found"))
                    }

                    val signedTxResult = walletManager.signTransaction(activityResultSender, txBytes)
                    if (signedTxResult.isFailure) {
                        val e = signedTxResult.exceptionOrNull()
                        transactionDao.updateStatus(tx.id, "PENDING", e?.message, System.currentTimeMillis())
                        transactionDao.incrementRetry(tx.id)
                        Log.e(TAG, "Transaction ${tx.id} signing failed", e)
                        continue
                    }
                    val signedTx = signedTxResult.getOrThrow()

                    var derivedSig = signatureBase58FromSignedTx(signedTx)
                    transactionDao.updateStatusWithSignature(
                        tx.id,
                        "SENDING",
                        null,
                        System.currentTimeMillis(),
                        derivedSig.ifBlank { null }
                    )

                    val confirmTimeoutMs = when (tx.type) {
                        "ACK_AWAKE" -> {
                            val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                            (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(2_000L)
                        }
                        "ACK_AWAKE_ATTESTED" -> {
                            val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                            (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(2_000L)
                        }
                        "CLAIM" -> {
                            val deadlineMillis = alarm.alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
                            val claimUntilMillis =
                                deadlineMillis + (OnchainParameters.CLAIM_GRACE_SECONDS * 1000L)
                            (claimUntilMillis - System.currentTimeMillis()).coerceAtLeast(2_000L)
                        }
                        else -> app.solarma.BuildConfig.SOLARMA_RPC_CONFIRM_TIMEOUT_MS
                    }.coerceAtMost(app.solarma.BuildConfig.SOLARMA_RPC_CONFIRM_TIMEOUT_MS)

                    var sendResult = rpcClient.sendAndConfirmTransactionFanout(
                        signedTx = signedTx,
                        confirmTimeoutMs = confirmTimeoutMs
                    )

                    // If the blockhash expired, rebuild + resign once (best-effort).
                    if (sendResult.isFailure && isBlockhashError(sendResult.exceptionOrNull()?.message)) {
                        Log.w(TAG, "Retrying ${tx.type} with fresh blockhash (txId=${tx.id})")
                        val rebuiltTxBytes = buildUnsignedTxOrSkip()
                        if (rebuiltTxBytes != null) {
                            val resigned = walletManager.signTransaction(activityResultSender, rebuiltTxBytes).getOrNull()
                            if (resigned != null) {
                                derivedSig = signatureBase58FromSignedTx(resigned)
                                transactionDao.updateStatusWithSignature(
                                    tx.id,
                                    "SENDING",
                                    null,
                                    System.currentTimeMillis(),
                                    derivedSig.ifBlank { null }
                                )
                                sendResult = rpcClient.sendAndConfirmTransactionFanout(
                                    signedTx = resigned,
                                    confirmTimeoutMs = confirmTimeoutMs
                                )
                            }
                        }
                    }

                    if (sendResult.isFailure) {
                        val e = sendResult.exceptionOrNull()

                        // CREATE may already be confirmed even if send/confirm errored.
                        if (tx.type == "CREATE_ALARM") {
                            val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                            val derivedAlarmPda = transactionBuilder.instructionBuilder
                                .deriveAlarmPda(owner, createAlarmId)
                                .address
                                .toBase58()
                            val exists = rpcClient.accountExists(derivedAlarmPda).getOrNull() == true
                            if (exists) {
                                alarmDao.update(
                                    alarm.copy(
                                        onchainPubkey = derivedAlarmPda,
                                        hasDeposit = true
                                    )
                                )
                                ensureStatsRow()
                                if (alarm.depositLamports > 0) {
                                    statsDao.addDeposit(alarm.depositLamports)
                                }
                                transactionDao.updateStatusWithSignature(
                                    tx.id,
                                    "CONFIRMED",
                                    null,
                                    System.currentTimeMillis(),
                                    derivedSig.ifBlank { null }
                                )
                                Log.i(TAG, "Create alarm confirmed despite error: ${e?.message}")
                                continue
                            }
                        }

                        val msg = e?.message ?: "Send failed"
                        val retryable = isRetryableError(msg)
                        transactionDao.updateStatusWithSignature(
                            tx.id,
                            if (retryable) "PENDING" else "FAILED",
                            msg,
                            System.currentTimeMillis(),
                            derivedSig.ifBlank { null }
                        )
                        if (retryable) {
                            transactionDao.incrementRetry(tx.id)
                        }
                        Log.e(TAG, "Transaction ${tx.id} send/confirm failed (retryable=$retryable): $msg", e)
                        continue
                    }

                    val signature = sendResult.getOrThrow()

                    ensureStatsRow()

                    applyConfirmedSideEffects(tx, alarm, owner)

                    transactionDao.updateStatusWithSignature(
                        tx.id,
                        "CONFIRMED",
                        null,
                        System.currentTimeMillis(),
                        signature
                    )
                    Log.i(TAG, "Transaction ${tx.id} confirmed")
                } catch (e: Exception) {
                    val delayMs = BASE_DELAY_MS * (1 shl tx.retryCount.coerceAtMost(5))
                    transactionDao.updateStatus(tx.id, "PENDING", e.message, System.currentTimeMillis())
                    transactionDao.incrementRetry(tx.id)
                    Log.e(TAG, "Transaction ${tx.id} failed, retry in ${delayMs}ms", e)
                }
            }
        } finally {
            processingMutex.unlock()
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun computeSnoozeCost(remaining: Long, snoozeCount: Int): Long {
        if (remaining <= 0) return 0
        val baseCost = remaining * OnchainParameters.SNOOZE_BASE_PERCENT / 100
        if (baseCost <= 0) return 0
        val safeCount = snoozeCount.coerceAtMost(30)
        val multiplier = 1L shl safeCount
        // Guard against overflow: if baseCost * multiplier exceeds Long range,
        // the result wraps negative/garbage. Cap at remaining instead.
        val cost = if (multiplier > 0 && baseCost > Long.MAX_VALUE / multiplier) {
            remaining  // overflow → cap at total remaining
        } else {
            baseCost * multiplier
        }
        return min(cost, remaining)
    }

    private suspend fun applyConfirmedSideEffects(
        tx: PendingTransaction,
        alarm: app.solarma.data.local.AlarmEntity,
        owner: org.sol4k.PublicKey
    ) {
        when (tx.type) {
            "CREATE_ALARM" -> {
                val createAlarmId = alarm.onchainAlarmId ?: alarm.id
                val alarmPda = transactionBuilder.instructionBuilder
                    .deriveAlarmPda(owner, createAlarmId)
                    .address
                    .toBase58()
                alarmDao.update(
                    alarm.copy(
                        onchainPubkey = alarmPda,
                        hasDeposit = true
                    )
                )
                if (alarm.depositLamports > 0) {
                    statsDao.addDeposit(alarm.depositLamports)
                }
            }

            "CLAIM", "SWEEP_ACKNOWLEDGED" -> {
                if (alarm.depositLamports > 0) {
                    statsDao.addSaved(alarm.depositLamports)
                }
                // Alarm fully resolved — delete from DB
                alarmDao.deleteById(alarm.id)
                Log.i(TAG, "Alarm ${alarm.id} deleted after ${tx.type.lowercase()}")
            }

            "ACK_AWAKE" -> {
                // Proof recorded on-chain. Claim will follow.
                Log.i(TAG, "Alarm ${alarm.id} wake proof acknowledged on-chain")
            }

            "ACK_AWAKE_ATTESTED" -> {
                Log.i(TAG, "Alarm ${alarm.id} wake proof acknowledged on-chain (attested)")
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
                    val penalty =
                        alarm.depositLamports * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
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
                        snoozeCount = alarm.snoozeCount + 1
                    )
                )
            }
        }
    }

    private fun resolveAlarmPda(
        alarm: app.solarma.data.local.AlarmEntity,
        owner: org.sol4k.PublicKey
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

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun computeProofHashForAttestation(alarm: app.solarma.data.local.AlarmEntity): ByteArray? {
        return when (alarm.wakeProofType) {
            WakeProofEngine.TYPE_NFC -> {
                val decoded = decodeHexToBytesOrNull(alarm.tagHash) ?: return null
                if (decoded.size != 32) return null
                decoded
            }
            WakeProofEngine.TYPE_QR -> {
                val code = alarm.qrCode ?: return null
                sha256(code.toByteArray(Charsets.UTF_8))
            }
            WakeProofEngine.TYPE_STEPS -> {
                // Steps proof doesn't have a compact "proof payload" today; we hash a stable description
                // so the server can attest to the same value deterministically.
                val completedAt = alarm.lastCompletedAt ?: 0L
                val s = "steps|${alarm.id}|${alarm.targetSteps}|$completedAt"
                sha256(s.toByteArray(Charsets.UTF_8))
            }
            else -> {
                val completedAt = alarm.lastCompletedAt ?: 0L
                val s = "none|${alarm.id}|$completedAt"
                sha256(s.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
