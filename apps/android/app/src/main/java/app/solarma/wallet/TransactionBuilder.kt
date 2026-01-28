package app.solarma.wallet

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sol4k.*
import org.sol4k.instruction.Instruction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds complete transactions for Solarma operations.
 * Handles blockhash fetching and transaction assembly.
 */
@Singleton
class TransactionBuilder @Inject constructor(
    private val rpcClient: SolanaRpcClient,
    private val instructionBuilder: SolarmaInstructionBuilder
) {
    companion object {
        private const val TAG = "Solarma.TxBuilder"
        
        // Burn sink address (matches BURN_SINK in constants.rs)
        val BURN_SINK = PublicKey(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
            )
        )
    }
    
    /**
     * Build create_alarm transaction.
     * Returns serialized transaction ready for MWA signing.
     */
    suspend fun buildCreateAlarmTransaction(
        owner: PublicKey,
        alarmId: Long,
        alarmTimeUnix: Long,
        deadlineUnix: Long,
        depositLamports: Long,
        penaltyRoute: PenaltyRoute,
        buddyAddress: PublicKey? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building create_alarm tx: alarmId=$alarmId, deposit=$depositLamports")
        
        val instruction = instructionBuilder.buildCreateAlarm(
            owner = owner,
            alarmId = alarmId,
            alarmTime = alarmTimeUnix,
            deadline = deadlineUnix,
            depositLamports = depositLamports,
            penaltyRoute = penaltyRoute.code,
            penaltyDestination = if (penaltyRoute == PenaltyRoute.BUDDY) buddyAddress else null
        )
        
        buildTransaction(owner, listOf(instruction))
    }
    
    /**
     * Build claim transaction.
     */
    suspend fun buildClaimTransaction(
        owner: PublicKey,
        alarmId: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building claim tx: alarmId=$alarmId")
        
        val (alarmPda, _) = instructionBuilder.deriveAlarmPda(owner, alarmId)
        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = alarmPda,
            vaultBump = 0 // Bump is stored in alarm account, not needed for derivation
        )
        
        buildTransaction(owner, listOf(instruction))
    }
    
    /**
     * Build snooze transaction.
     */
    suspend fun buildSnoozeTransaction(
        owner: PublicKey,
        alarmId: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building snooze tx: alarmId=$alarmId")
        
        val (alarmPda, _) = instructionBuilder.deriveAlarmPda(owner, alarmId)
        val instruction = instructionBuilder.buildSnooze(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = BURN_SINK
        )
        
        buildTransaction(owner, listOf(instruction))
    }
    
    /**
     * Build emergency_refund transaction.
     */
    suspend fun buildEmergencyRefundTransaction(
        owner: PublicKey,
        alarmId: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building emergency_refund tx: alarmId=$alarmId")
        
        val (alarmPda, _) = instructionBuilder.deriveAlarmPda(owner, alarmId)
        val instruction = instructionBuilder.buildEmergencyRefund(
            owner = owner,
            alarmPda = alarmPda
        )
        
        buildTransaction(owner, listOf(instruction))
    }
    
    /**
     * Build and serialize a transaction.
     */
    private suspend fun buildTransaction(
        feePayer: PublicKey,
        instructions: List<Instruction>
    ): ByteArray {
        // Get recent blockhash
        val blockhash = rpcClient.getLatestBlockhash()
        
        // Build transaction
        val transaction = Transaction(
            recentBlockhash = blockhash,
            feePayer = feePayer,
            instructions = instructions
        )
        
        // Return serialized (unsigned) transaction
        return transaction.serialize()
    }
}

/**
 * Penalty route enum matching Anchor.
 */
enum class PenaltyRoute(val code: Byte) {
    BURN(0),
    DONATE(1),
    BUDDY(2)
}
