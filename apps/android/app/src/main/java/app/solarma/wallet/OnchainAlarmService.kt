package app.solarma.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import app.solarma.data.local.AlarmEntity
import app.solarma.alarm.AlarmTiming
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.qualifiers.ApplicationContext
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
class OnchainAlarmService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val transactionBuilder: TransactionBuilder,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "Solarma.OnchainService"

        private const val CONFIRM_ATTEMPTS = 15
        private const val CONFIRM_DELAY_MS = 1500L
    }

    class PendingConfirmationException(
        val signature: String,
        val pda: String
    ) : Exception("Transaction pending confirmation")
    
    /**
     * Check if network is available.
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
        buddyAddress: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check network first
            if (!isNetworkAvailable()) {
                return@withContext Result.failure(Exception("No internet connection. Please connect to the internet and try again."))
            }
            
            val ownerAddress = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            val ownerPubkey = PublicKey(ownerAddress)
            val alarmId = alarm.onchainAlarmId ?: alarm.id
            
            // Calculate deadline (alarm_time + grace period)
            val alarmTimeUnix = alarm.alarmTimeMillis / 1000
            val deadlineUnix = alarmTimeUnix + AlarmTiming.GRACE_PERIOD_SECONDS
            
            Log.i(TAG, "Creating onchain alarm: id=$alarmId, deposit=$depositLamports")
            
            // Validate and parse buddy address if provided
            val buddyPubkey = buddyAddress?.let { addr ->
                try {
                    if (addr.isBlank()) null
                    else PublicKey(addr)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid buddy address format: $addr", e)
                    return@withContext Result.failure(
                        Exception("Invalid buddy address. Please enter a valid Solana wallet address (base58)")
                    )
                }
            }
            
            // Build transaction
            val txBytes = transactionBuilder.buildCreateAlarmTransaction(
                owner = ownerPubkey,
                alarmId = alarmId,
                alarmTimeUnix = alarmTimeUnix,
                deadlineUnix = deadlineUnix,
                depositLamports = depositLamports,
                penaltyRoute = penaltyRoute,
                buddyAddress = buddyPubkey
            )
            
            // Sign and send via MWA
            val result = walletManager.signAndSendTransaction(activityResultSender, txBytes)
            val signature = result.getOrElse { e ->
                Log.e(TAG, "Failed to create onchain alarm", e)
                return@withContext Result.failure(e)
            }

            Log.i(TAG, "Alarm created onchain: signature=$signature")

            val alarmPda = transactionBuilder.instructionBuilder.deriveAlarmPda(ownerPubkey, alarmId)
            when (val confirmation = awaitConfirmation(signature)) {
                is ConfirmationResult.Confirmed -> Result.success(alarmPda.address.toBase58())
                is ConfirmationResult.Failed ->
                    Result.failure(Exception("Transaction failed: ${confirmation.error}"))
                is ConfirmationResult.Pending ->
                    Result.failure(PendingConfirmationException(signature, alarmPda.address.toBase58()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create onchain alarm", e)
            Result.failure(e)
        }
    }
    
    /**
     * Claim deposit after completing wake proof.
     */
    suspend fun claimDeposit(
        activityResultSender: ActivityResultSender,
        alarm: AlarmEntity
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ownerAddress = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Claiming deposit for alarm: ${alarm.id}")
            
            val owner = PublicKey(ownerAddress)
            val alarmPda = resolveAlarmPda(alarm, owner)
            val txBytes = transactionBuilder.buildClaimTransactionByPubkey(
                owner = owner,
                alarmPda = alarmPda
            )
            
            val result = walletManager.signAndSendTransaction(activityResultSender, txBytes)
            val signature = result.getOrElse { e ->
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
        alarm: AlarmEntity
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ownerAddress = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Snoozing alarm: ${alarm.id}")
            
            val owner = PublicKey(ownerAddress)
            val alarmPda = resolveAlarmPda(alarm, owner)
            val txBytes = transactionBuilder.buildSnoozeTransactionByPubkey(
                owner = owner,
                alarmPda = alarmPda,
                snoozeCount = alarm.snoozeCount
            )
            
            val result = walletManager.signAndSendTransaction(activityResultSender, txBytes)
            val signature = result.getOrElse { e ->
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
        alarm: AlarmEntity
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ownerAddress = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Emergency refund for alarm: ${alarm.id}")
            
            val owner = PublicKey(ownerAddress)
            val alarmPda = resolveAlarmPda(alarm, owner)
            val txBytes = transactionBuilder.buildEmergencyRefundTransactionByPubkey(
                owner = owner,
                alarmPda = alarmPda
            )
            
            val result = walletManager.signAndSendTransaction(activityResultSender, txBytes)
            val signature = result.getOrElse { e ->
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

    private sealed class ConfirmationResult {
        object Confirmed : ConfirmationResult()
        data class Failed(val error: String) : ConfirmationResult()
        object Pending : ConfirmationResult()
    }

    private suspend fun awaitConfirmation(signature: String): ConfirmationResult {
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

    private fun resolveAlarmPda(alarm: AlarmEntity, owner: PublicKey): PublicKey {
        val onchainPubkey = alarm.onchainPubkey
        if (!onchainPubkey.isNullOrBlank()) {
            return PublicKey(onchainPubkey)
        }
        val alarmId = alarm.onchainAlarmId ?: alarm.id
        return transactionBuilder.instructionBuilder.deriveAlarmPda(owner, alarmId).address
    }
    
    /**
     * M3: Sync on-chain alarms with the local Room database.
     * Fetches all program accounts owned by the current wallet and inserts
     * any missing alarms into the local DB. This handles multi-device scenarios
     * where an alarm was created on another device.
     *
     * @param alarmDao the DAO to check/insert local alarms
     * @return number of new alarms synced from on-chain, or -1 on error
     */
    suspend fun syncOnchainAlarms(alarmDao: app.solarma.data.local.AlarmDao): Int = 
        withContext(Dispatchers.IO) {
            try {
                if (!isNetworkAvailable()) return@withContext 0
                
                val ownerBase58 = walletManager.getConnectedWallet() ?: return@withContext 0
                val programId = SolarmaInstructionBuilder.PROGRAM_ID.toBase58()
                
                val result = rpcClient.getProgramAccounts(programId, ownerBase58)
                val accounts = result.getOrNull() ?: return@withContext -1
                
                var synced = 0
                for (account in accounts) {
                    // Check if this on-chain alarm exists locally
                    val existing = alarmDao.getByOnchainPubkey(account.pubkey)
                    if (existing != null) continue  // Already in local DB
                    
                    // Decode on-chain alarm data
                    try {
                        val data = android.util.Base64.decode(account.dataBase64, android.util.Base64.DEFAULT)
                        // Minimum size: 8 disc + 32 owner + 8 id + 8 time + 8 deadline
                        //   + 8 initial + 8 remaining + 1 route + 1+32 dest + 1 snooze + 1 status + 1 bump + 1 vault_bump = 110
                        if (data.size < 110) continue
                        
                        // Parse key fields from Borsh-serialized data
                        // Rust Alarm struct layout (state.rs):
                        //   8 disc | 32 owner | 8 alarm_id | 8 alarm_time | 8 deadline |
                        //   8 initial_amount | 8 remaining_amount | 1 penalty_route |
                        //   1+32 penalty_destination (Option<Pubkey>) | 1 snooze_count |
                        //   1 status | 1 bump | 1 vault_bump | 64 padding
                        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buf.position(8)  // skip discriminator
                        buf.position(buf.position() + 32) // skip owner
                        val alarmId = buf.getLong()         // alarm_id (u64)
                        val alarmTime = buf.getLong()       // alarm_time (unix sec)
                        val deadline = buf.getLong()        // deadline (unix sec)
                        val initialAmount = buf.getLong()   // initial_amount
                        val remainingAmount = buf.getLong() // remaining_amount
                        val penaltyRoute = buf.get().toInt() // penalty_route
                        // penalty_destination: Option<Pubkey> = 1 byte tag + 32 bytes if Some
                        val hasDestination = buf.get().toInt() and 0xFF
                        if (hasDestination != 0) {
                            buf.position(buf.position() + 32) // skip Pubkey bytes
                        }
                        val snoozeCount = buf.get().toInt() and 0xFF
                        val status = buf.get().toInt() and 0xFF
                        
                        // AlarmStatus: Created=0, Acknowledged=1, Claimed=2, Slashed=3
                        // Skip completed alarms (Claimed=2 or Slashed=3)
                        if (status >= 2) continue
                        
                        // Create local alarm record for this on-chain alarm
                        val alarm = AlarmEntity(
                            alarmTimeMillis = alarmTime * 1000,
                            label = "Synced from chain",
                            isEnabled = true,
                            hasDeposit = true,
                            depositLamports = initialAmount,
                            penaltyRoute = penaltyRoute,
                            onchainPubkey = account.pubkey,
                            snoozeCount = snoozeCount,
                            wakeProofType = 0  // Default — user can reconfigure
                        )
                        alarmDao.insert(alarm)
                        synced++
                        Log.i(TAG, "Synced on-chain alarm: ${account.pubkey} (time=$alarmTime, remaining=$remainingAmount)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode on-chain alarm: ${account.pubkey}", e)
                    }
                }
                
                if (synced > 0) {
                    Log.i(TAG, "M3: Synced $synced on-chain alarms to local DB")
                }
                synced
            } catch (e: Exception) {
                Log.e(TAG, "syncOnchainAlarms failed", e)
                -1
            }
        }
}
