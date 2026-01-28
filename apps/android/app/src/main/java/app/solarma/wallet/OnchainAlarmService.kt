package app.solarma.wallet

import android.util.Log
import app.solarma.data.local.AlarmEntity
import kotlinx.coroutines.Dispatchers
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
    private val walletManager: WalletManager,
    private val transactionBuilder: TransactionBuilder,
    private val transactionQueue: TransactionQueue,
    private val rpcClient: SolanaRpcClient
) {
    companion object {
        private const val TAG = "Solarma.OnchainService"
        
        // Grace period: 30 minutes after alarm_time
        private const val GRACE_PERIOD_SECONDS = 1800L
    }
    
    /**
     * Create onchain alarm with deposit.
     * Returns the alarm PDA pubkey for local storage.
     */
    suspend fun createOnchainAlarm(
        alarm: AlarmEntity,
        depositLamports: Long,
        penaltyRoute: PenaltyRoute,
        buddyAddress: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val owner = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            val ownerPubkey = PublicKey(owner)
            val alarmId = alarm.id // Use local DB ID as alarm_id
            
            // Calculate deadline (alarm_time + grace period)
            val alarmTimeUnix = alarm.alarmTimeMillis / 1000
            val deadlineUnix = alarmTimeUnix + GRACE_PERIOD_SECONDS
            
            Log.i(TAG, "Creating onchain alarm: id=$alarmId, deposit=$depositLamports")
            
            // Build transaction
            val txBytes = transactionBuilder.buildCreateAlarmTransaction(
                owner = ownerPubkey,
                alarmId = alarmId,
                alarmTimeUnix = alarmTimeUnix,
                deadlineUnix = deadlineUnix,
                depositLamports = depositLamports,
                penaltyRoute = penaltyRoute,
                buddyAddress = buddyAddress?.let { PublicKey(it) }
            )
            
            // Sign via MWA
            val signedTx = walletManager.signTransaction(txBytes)
                ?: return@withContext Result.failure(Exception("Transaction signing failed"))
            
            // Submit to network
            val signature = submitTransaction(signedTx)
            
            Log.i(TAG, "Alarm created onchain: signature=$signature")
            
            // Return alarm PDA for local storage
            val (alarmPda, _) = transactionBuilder.instructionBuilder.deriveAlarmPda(ownerPubkey, alarmId)
            Result.success(alarmPda.toBase58())
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create onchain alarm", e)
            Result.failure(e)
        }
    }
    
    /**
     * Claim deposit after completing wake proof.
     */
    suspend fun claimDeposit(alarm: AlarmEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            val owner = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Claiming deposit for alarm: ${alarm.id}")
            
            val txBytes = transactionBuilder.buildClaimTransaction(
                owner = PublicKey(owner),
                alarmId = alarm.id
            )
            
            val signedTx = walletManager.signTransaction(txBytes)
                ?: return@withContext Result.failure(Exception("Transaction signing failed"))
            
            val signature = submitTransaction(signedTx)
            Log.i(TAG, "Deposit claimed: signature=$signature")
            
            Result.success(signature)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to claim deposit", e)
            Result.failure(e)
        }
    }
    
    /**
     * Snooze alarm (reduces deposit).
     */
    suspend fun snoozeAlarm(alarm: AlarmEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            val owner = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Snoozing alarm: ${alarm.id}")
            
            val txBytes = transactionBuilder.buildSnoozeTransaction(
                owner = PublicKey(owner),
                alarmId = alarm.id
            )
            
            val signedTx = walletManager.signTransaction(txBytes)
                ?: return@withContext Result.failure(Exception("Transaction signing failed"))
            
            val signature = submitTransaction(signedTx)
            Log.i(TAG, "Alarm snoozed: signature=$signature")
            
            Result.success(signature)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to snooze alarm", e)
            Result.failure(e)
        }
    }
    
    /**
     * Emergency refund before alarm time.
     */
    suspend fun emergencyRefund(alarm: AlarmEntity): Result<String> = withContext(Dispatchers.IO) {
        try {
            val owner = walletManager.getConnectedWallet()
                ?: return@withContext Result.failure(Exception("Wallet not connected"))
            
            Log.i(TAG, "Emergency refund for alarm: ${alarm.id}")
            
            val txBytes = transactionBuilder.buildEmergencyRefundTransaction(
                owner = PublicKey(owner),
                alarmId = alarm.id
            )
            
            val signedTx = walletManager.signTransaction(txBytes)
                ?: return@withContext Result.failure(Exception("Transaction signing failed"))
            
            val signature = submitTransaction(signedTx)
            Log.i(TAG, "Emergency refund complete: signature=$signature")
            
            Result.success(signature)
            
        } catch (e: Exception) {
            Log.e(TAG, "Emergency refund failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Submit signed transaction to network.
     */
    private suspend fun submitTransaction(signedTx: ByteArray): String {
        // Queue for retry handling
        val txId = transactionQueue.enqueue(signedTx, "solarma_tx")
        
        // For immediate feedback, also try direct submission
        // TransactionProcessor will handle retries if this fails
        return try {
            val response = rpcClient.sendTransaction(signedTx)
            response.getOrThrow()
        } catch (e: Exception) {
            Log.w(TAG, "Direct submit failed, queued for retry: $txId")
            txId.toString()
        }
    }
}
