package app.solarma.wallet

import android.util.Log
import app.solarma.alarm.AlarmTiming
import app.solarma.data.local.AlarmEntity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.sol4k.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that handles the complete onchain flow for alarms.
 * Bridges local alarms with Solana transactions.
 */
@Singleton
class OnchainAlarmService
    @Inject
    constructor(
        private val networkChecker: NetworkChecker,
        private val walletManager: WalletManager,
        private val transactionBuilder: TransactionBuilder,
        private val rpcClient: SolanaRpcClient,
        private val deviceKeyStore: DeviceKeyStore,
    ) {
        companion object {
            private const val TAG = "Solarma.OnchainService"

            private const val CONFIRM_ATTEMPTS = 15
            private const val CONFIRM_DELAY_MS = 1500L
        }

        class PendingConfirmationException(
            val signature: String,
            val pda: String,
        ) : Exception("Transaction pending confirmation")

        /**
         * Sign via MWA, then fan-out to all RPCs for maximum landing probability.
         */
        private suspend fun signAndSendFanOut(
            activityResultSender: ActivityResultSender,
            txBytes: ByteArray,
        ): Result<String> {
            val signResult = walletManager.signTransaction(activityResultSender, txBytes)
            val signedTx = signResult.getOrElse { return Result.failure(it) }
            return rpcClient.sendTransactionFanOut(signedTx)
        }

        /**
         * Create onchain alarm with deposit.
         * Returns the alarm PDA pubkey for local storage.
         */
        suspend fun createOnchainAlarm(
            activityResultSender: ActivityResultSender,
            alarm: AlarmEntity,
            depositLamports: Long,
            penaltyRoute: PenaltyRoute,
            buddyAddress: String? = null,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                var signature: String? = null
                var pda: String? = null
                try {
                    // Check network first
                    if (!networkChecker.isNetworkAvailable()) {
                        return@withContext Result.failure(
                            Exception(
                                "No internet connection. " +
                                    "Please connect to the internet and try again.",
                            ),
                        )
                    }

                    val ownerAddress =
                        walletManager.getConnectedWallet()
                            ?: return@withContext Result.failure(Exception("Wallet not connected"))

                    val ownerPubkey = PublicKey(ownerAddress)
                    val alarmId = alarm.onchainAlarmId ?: alarm.id

                    // Calculate deadline (alarm_time + grace period)
                    val alarmTimeUnix = alarm.alarmTimeMillis / 1000
                    val deadlineUnix = alarmTimeUnix + AlarmTiming.GRACE_PERIOD_SECONDS

                    Log.i(TAG, "Creating onchain alarm: id=$alarmId, deposit=$depositLamports")

                    // Validate and parse buddy address if provided
                    val buddyPubkey =
                        buddyAddress?.let { addr ->
                            try {
                                if (addr.isBlank()) {
                                    null
                                } else {
                                    PublicKey(addr)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Invalid buddy address format: $addr", e)
                                return@withContext Result.failure(
                                    Exception("Invalid buddy address. Please enter a valid Solana wallet address (base58)"),
                                )
                            }
                        }

                    val alarmPda = transactionBuilder.instructionBuilder.deriveAlarmPda(ownerPubkey, alarmId).address.toBase58()
                    pda = alarmPda

                    // This device's key may then return the deposit without the wallet; top it
                    // up for fees in the same transaction when it runs low.
                    val device =
                        try {
                            deviceKeyStore.getOrCreate()
                        } catch (e: Exception) {
                            Log.e(TAG, "Device key unavailable; alarm will need the wallet to claim", e)
                            null
                        }
                    val topUp =
                        device?.let { key ->
                            rpcClient.getBalance(key.publicKey.toBase58()).getOrNull()?.let { balance ->
                                if (DeviceKeyStore.needsTopUp(balance)) DeviceKeyStore.TOP_UP_LAMPORTS else 0L
                            }
                        } ?: 0L

                    // Build inside the wallet session (fresh blockhash), then sign
                    val signedTx =
                        walletManager.signTransaction(activityResultSender, ownerAddress) {
                            transactionBuilder.buildCreateAlarmTransaction(
                                owner = ownerPubkey,
                                alarmId = alarmId,
                                alarmTimeUnix = alarmTimeUnix,
                                deadlineUnix = deadlineUnix,
                                depositLamports = depositLamports,
                                penaltyRoute = penaltyRoute,
                                buddyAddress = buddyPubkey,
                                ackAuthority = device?.publicKey,
                                ackAuthorityTopUpLamports = topUp,
                            )
                        }.getOrElse { e ->
                            // Not signed: nothing was sent
                            Log.e(TAG, "Failed to sign onchain alarm", e)
                            return@withContext Result.failure(e)
                        }
                    // From here on the deposit may land: never report a plain failure unless
                    // the transaction definitely did not (or cannot) land.
                    signature = Base58.firstSignatureOf(signedTx)

                    rpcClient.sendTransactionFanOut(signedTx).onFailure { e ->
                        return@withContext if (ErrorClassifier.programErrorCode(e.message) != null) {
                            // Rejected in preflight simulation: not forwarded
                            Result.failure(e)
                        } else {
                            Result.failure(PendingConfirmationException(signature!!, alarmPda))
                        }
                    }

                    Log.i(TAG, "Alarm created onchain: signature=$signature")

                    when (val confirmation = awaitConfirmation(signature!!)) {
                        is ConfirmationResult.Confirmed -> Result.success(alarmPda)
                        is ConfirmationResult.Failed ->
                            if (rpcClient.accountExists(alarmPda).getOrNull() == true) {
                                // "Already in use": an earlier attempt created it
                                Result.success(alarmPda)
                            } else {
                                Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                            }
                        is ConfirmationResult.Pending ->
                            Result.failure(PendingConfirmationException(signature!!, alarmPda))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create onchain alarm", e)
                    val sent = signature
                    if (sent != null && pda != null) {
                        // Signed and possibly sent: let the queue confirm or retry it
                        Result.failure(PendingConfirmationException(sent, pda))
                    } else {
                        Result.failure(e)
                    }
                }
            }

        /**
         * Claim deposit after completing wake proof.
         */
        suspend fun claimDeposit(
            activityResultSender: ActivityResultSender,
            alarm: AlarmEntity,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val ownerAddress =
                        walletManager.getConnectedWallet()
                            ?: return@withContext Result.failure(Exception("Wallet not connected"))

                    Log.i(TAG, "Claiming deposit for alarm: ${alarm.id}")

                    val owner = PublicKey(ownerAddress)
                    val alarmPda = resolveAlarmPda(alarm, owner)
                    val txBytes =
                        transactionBuilder.buildClaimTransactionByPubkey(
                            owner = owner,
                            alarmPda = alarmPda,
                        )

                    val result = signAndSendFanOut(activityResultSender, txBytes)
                    val signature =
                        result.getOrElse { e ->
                            Log.e(TAG, "Failed to claim deposit", e)
                            return@withContext Result.failure(e)
                        }

                    Log.i(TAG, "Deposit claimed: signature=$signature")
                    when (val confirmation = awaitConfirmation(signature)) {
                        is ConfirmationResult.Confirmed -> Result.success(signature)
                        is ConfirmationResult.Failed ->
                            Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                        is ConfirmationResult.Pending ->
                            Result.failure(Exception("Transaction pending confirmation"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to claim deposit", e)
                    Result.failure(e)
                }
            }

        /**
         * Snooze alarm (reduces deposit).
         */
        suspend fun snoozeAlarm(
            activityResultSender: ActivityResultSender,
            alarm: AlarmEntity,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val ownerAddress =
                        walletManager.getConnectedWallet()
                            ?: return@withContext Result.failure(Exception("Wallet not connected"))

                    Log.i(TAG, "Snoozing alarm: ${alarm.id}")

                    val owner = PublicKey(ownerAddress)
                    val alarmPda = resolveAlarmPda(alarm, owner)
                    val txBytes =
                        transactionBuilder.buildSnoozeTransactionByPubkey(
                            owner = owner,
                            alarmPda = alarmPda,
                            snoozeCount = alarm.snoozeCount,
                        )

                    val result = signAndSendFanOut(activityResultSender, txBytes)
                    val signature =
                        result.getOrElse { e ->
                            Log.e(TAG, "Failed to snooze alarm", e)
                            return@withContext Result.failure(e)
                        }

                    Log.i(TAG, "Alarm snoozed: signature=$signature")
                    when (val confirmation = awaitConfirmation(signature)) {
                        is ConfirmationResult.Confirmed -> Result.success(signature)
                        is ConfirmationResult.Failed ->
                            Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                        is ConfirmationResult.Pending ->
                            Result.failure(Exception("Transaction pending confirmation"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to snooze alarm", e)
                    Result.failure(e)
                }
            }

        /**
         * Emergency refund before alarm time.
         */
        suspend fun emergencyRefund(
            activityResultSender: ActivityResultSender,
            alarm: AlarmEntity,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val ownerAddress =
                        walletManager.getConnectedWallet()
                            ?: return@withContext Result.failure(Exception("Wallet not connected"))

                    Log.i(TAG, "Emergency refund for alarm: ${alarm.id}")

                    val owner = PublicKey(ownerAddress)
                    val alarmPda = resolveAlarmPda(alarm, owner)
                    val txBytes =
                        transactionBuilder.buildEmergencyRefundTransactionByPubkey(
                            owner = owner,
                            alarmPda = alarmPda,
                        )

                    val result = signAndSendFanOut(activityResultSender, txBytes)
                    val signature =
                        result.getOrElse { e ->
                            Log.e(TAG, "Emergency refund failed", e)
                            return@withContext Result.failure(e)
                        }

                    Log.i(TAG, "Emergency refund complete: signature=$signature")
                    when (val confirmation = awaitConfirmation(signature)) {
                        is ConfirmationResult.Confirmed -> Result.success(signature)
                        is ConfirmationResult.Failed ->
                            Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                        is ConfirmationResult.Pending ->
                            Result.failure(Exception("Transaction pending confirmation"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Emergency refund failed", e)
                    Result.failure(e)
                }
            }

        /**
         * Slash (resolve) an expired alarm after deadline.
         * Permissionless — anyone can call this after the deadline passes.
         */
        suspend fun slashAlarm(
            activityResultSender: ActivityResultSender,
            alarm: AlarmEntity,
        ): Result<String> =
            withContext(Dispatchers.IO) {
                try {
                    val ownerAddress =
                        walletManager.getConnectedWallet()
                            ?: return@withContext Result.failure(Exception("Wallet not connected"))

                    Log.i(TAG, "Slashing expired alarm: ${alarm.id}")

                    val owner = PublicKey(ownerAddress)
                    val alarmPda = resolveAlarmPda(alarm, owner)
                    val txBytes =
                        transactionBuilder.buildSlashTransactionByPubkey(
                            owner = owner,
                            alarmPda = alarmPda,
                            penaltyRoute = alarm.penaltyRoute,
                            penaltyDestination = alarm.penaltyDestination,
                        )

                    val result = signAndSendFanOut(activityResultSender, txBytes)
                    val signature =
                        result.getOrElse { e ->
                            Log.e(TAG, "Slash failed", e)
                            return@withContext Result.failure(e)
                        }

                    Log.i(TAG, "Slash complete: signature=$signature")
                    when (val confirmation = awaitConfirmation(signature)) {
                        is ConfirmationResult.Confirmed -> Result.success(signature)
                        is ConfirmationResult.Failed ->
                            Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                        is ConfirmationResult.Pending ->
                            Result.failure(Exception("Transaction pending confirmation"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Slash failed", e)
                    Result.failure(e)
                }
            }

        internal sealed class ConfirmationResult {
            object Confirmed : ConfirmationResult()

            data class Failed(val error: String) : ConfirmationResult()

            object Pending : ConfirmationResult()
        }

        internal suspend fun awaitConfirmation(signature: String): ConfirmationResult {
            repeat(CONFIRM_ATTEMPTS) { attempt ->
                val status = rpcClient.getSignatureStatus(signature).getOrNull()
                if (status != null) {
                    if (status.err != null) {
                        Log.w(TAG, "Transaction failed: ${status.err}")
                        return ConfirmationResult.Failed(status.err)
                    }
                    if (status.confirmationStatus == "confirmed" || status.confirmationStatus == "finalized") {
                        return ConfirmationResult.Confirmed
                    }
                }
                Log.d(TAG, "Confirmation pending ($attempt/${CONFIRM_ATTEMPTS - 1})")
                delay(CONFIRM_DELAY_MS)
            }
            return ConfirmationResult.Pending
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
            return transactionBuilder.instructionBuilder.deriveAlarmPda(owner, alarmId).address
        }
    }
