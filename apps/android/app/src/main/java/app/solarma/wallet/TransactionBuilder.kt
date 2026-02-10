package app.solarma.wallet

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds complete transactions for Solarma operations.
 * Handles blockhash fetching and transaction assembly.
 */
@Singleton
class TransactionBuilder @Inject constructor(
    private val rpcClient: SolanaRpcClient,
    val instructionBuilder: SolarmaInstructionBuilder
) {
    companion object {
        private const val TAG = "Solarma.TxBuilder"
        
        // Burn sink address (matches BURN_SINK in constants.rs)
        val BURN_SINK = PublicKey("1nc1nerator11111111111111111111111111111111")
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
            penaltyDestination = when (penaltyRoute) {
                PenaltyRoute.BUDDY -> buddyAddress
                PenaltyRoute.DONATE -> PublicKey(SolarmaTreasury.ADDRESS)
                else -> null
            }
        )
        
        buildTransaction(owner, instruction)
    }
    
    /**
     * Build claim transaction.
     */
    suspend fun buildClaimTransaction(
        owner: PublicKey,
        alarmId: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building claim tx: alarmId=$alarmId")
        
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, alarmId)
        buildClaimTransactionByPubkey(owner, alarmPda.address)
    }

    /**
     * Build claim transaction using alarm PDA directly.
     */
    suspend fun buildClaimTransactionByPubkey(
        owner: PublicKey,
        alarmPda: PublicKey
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building claim tx: alarmPda=${alarmPda.toBase58()}")

        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = alarmPda
        )

        buildTransaction(owner, instruction)
    }
    
    /**
     * H3: Build ack_awake transaction to record wake proof on-chain.
     */
    suspend fun buildAckAwakeTransactionByPubkey(
        owner: PublicKey,
        alarmPda: PublicKey
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building ack_awake tx: alarmPda=${alarmPda.toBase58()}")

        val instruction = instructionBuilder.buildAckAwake(
            owner = owner,
            alarmPda = alarmPda
        )

        buildTransaction(owner, instruction)
    }
    
    /**
     * Build snooze transaction.
     */
    suspend fun buildSnoozeTransaction(
        owner: PublicKey,
        alarmId: Long,
        snoozeCount: Int = 0
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building snooze tx: alarmId=$alarmId")
        
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, alarmId)
        buildSnoozeTransactionByPubkey(owner, alarmPda.address, snoozeCount)
    }

    /**
     * Build snooze transaction using alarm PDA directly.
     */
    suspend fun buildSnoozeTransactionByPubkey(
        owner: PublicKey,
        alarmPda: PublicKey,
        snoozeCount: Int = 0
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building snooze tx: alarmPda=${alarmPda.toBase58()}, snoozeCount=$snoozeCount")

        val instruction = instructionBuilder.buildSnooze(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = BURN_SINK,
            expectedSnoozeCount = snoozeCount
        )

        buildTransaction(owner, instruction)
    }
    
    /**
     * Build emergency_refund transaction.
     */
    suspend fun buildEmergencyRefundTransaction(
        owner: PublicKey,
        alarmId: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building emergency_refund tx: alarmId=$alarmId")
        
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, alarmId)
        buildEmergencyRefundTransactionByPubkey(owner, alarmPda.address)
    }

    /**
     * Build emergency_refund transaction using alarm PDA directly.
     */
    suspend fun buildEmergencyRefundTransactionByPubkey(
        owner: PublicKey,
        alarmPda: PublicKey
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building emergency_refund tx: alarmPda=${alarmPda.toBase58()}")

        val instruction = instructionBuilder.buildEmergencyRefund(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = BURN_SINK
        )

        buildTransaction(owner, instruction)
    }

    /**
     * Build slash transaction.
     */
    suspend fun buildSlashTransaction(
        owner: PublicKey,
        onchainAlarmId: Long,
        penaltyRoute: Int,
        penaltyDestination: String?
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building slash tx: alarmId=$onchainAlarmId, route=$penaltyRoute")

        val alarmPda = instructionBuilder.deriveAlarmPda(owner, onchainAlarmId)
        buildSlashTransactionByPubkey(owner, alarmPda.address, penaltyRoute, penaltyDestination)
    }

    /**
     * Build slash transaction using alarm PDA directly.
     */
    suspend fun buildSlashTransactionByPubkey(
        owner: PublicKey,
        alarmPda: PublicKey,
        penaltyRoute: Int,
        penaltyDestination: String?
    ): ByteArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "Building slash tx: alarmPda=${alarmPda.toBase58()}, route=$penaltyRoute")

        val route = PenaltyRoute.fromCode(penaltyRoute)
        val recipient = when (route) {
            PenaltyRoute.BURN -> BURN_SINK
            PenaltyRoute.DONATE -> PublicKey(penaltyDestination ?: SolarmaTreasury.ADDRESS)
            PenaltyRoute.BUDDY -> {
                val destination = penaltyDestination ?: throw IllegalArgumentException("Buddy address missing")
                PublicKey(destination)
            }
        }

        val instruction = instructionBuilder.buildSlash(
            caller = owner,
            alarmPda = alarmPda,
            penaltyRecipient = recipient
        )

        buildTransaction(owner, instruction)
    }
    
    /**
     * Build and serialize a transaction.
     */
    private suspend fun buildTransaction(
        feePayer: PublicKey,
        instruction: SolarmaInstruction
    ): ByteArray {
        // Get recent blockhash
        val blockhash = rpcClient.getLatestBlockhash()
        Log.d(TAG, "Blockhash: $blockhash")
        
        // Build sorted account list with metadata
        val sortedAccounts = buildSortedAccountMetas(feePayer, instruction)
        Log.d(TAG, "Sorted accounts (${sortedAccounts.size}):")
        sortedAccounts.forEachIndexed { i, acc ->
            Log.d(TAG, "  [$i] ${acc.pubkey.toBase58()} signer=${acc.isSigner} writable=${acc.isWritable}")
        }
        
        // Log instruction accounts order
        Log.d(TAG, "Instruction accounts order:")
        instruction.accounts.forEachIndexed { i, meta ->
            val idx = sortedAccounts.indexOfFirst { it.pubkey.toBase58() == meta.pubkey.toBase58() }
            Log.d(TAG, "  [$i] -> index $idx (${meta.pubkey.toBase58()})")
        }
        
        // Log instruction data
        Log.d(TAG, "Instruction data (${instruction.data.size} bytes): ${instruction.data.joinToString("") { "%02x".format(it) }}")
        
        // Build message with proper header
        val message = buildMessage(blockhash, sortedAccounts, instruction)
        
        // Return message for MWA signing
        val tx = buildUnsignedTransaction(message)
        Log.i(TAG, "Final TX size: ${tx.size} bytes")
        Log.d(TAG, "Final TX hex: ${tx.joinToString("") { "%02x".format(it) }}")
        return tx
    }

    /**
     * Build an unsigned transaction deterministically using a caller-provided blockhash.
     *
     * This exists to enable "transaction snapshot tests" that validate:
     * - program id
     * - account metas and ordering
     * - compiled instruction indices
     * - message/transaction serialization
     *
     * It does NOT touch the network (no RPC calls).
     */
    internal fun buildUnsignedTransactionForSnapshot(
        feePayer: PublicKey,
        instruction: SolarmaInstruction,
        recentBlockhash: String
    ): ByteArray {
        val sortedAccounts = buildSortedAccountMetas(feePayer, instruction)
        val message = buildMessage(recentBlockhash, sortedAccounts, instruction)
        return buildUnsignedTransaction(message)
    }
    
    /**
     * Account info for sorting and header computation
     */
    private data class SortedAccountMeta(
        val pubkey: PublicKey,
        val isSigner: Boolean,
        val isWritable: Boolean
    )
    
    /**
     * Build unique sorted account metas list.
     * Solana ordering: writable signers, readonly signers, writable non-signers, readonly non-signers
     */
    private fun buildSortedAccountMetas(
        feePayer: PublicKey,
        instruction: SolarmaInstruction
    ): List<SortedAccountMeta> {
        val accountMap = mutableMapOf<String, SortedAccountMeta>()
        
        // Fee payer is always first signer and writable
        accountMap[feePayer.toBase58()] = SortedAccountMeta(feePayer, isSigner = true, isWritable = true)
        
        // Add instruction accounts
        for (meta in instruction.accounts) {
            val key = meta.pubkey.toBase58()
            val existing = accountMap[key]
            if (existing != null) {
                // Merge flags - more permissive wins
                accountMap[key] = SortedAccountMeta(
                    existing.pubkey,
                    existing.isSigner || meta.isSigner,
                    existing.isWritable || meta.isWritable
                )
            } else {
                accountMap[key] = SortedAccountMeta(meta.pubkey, meta.isSigner, meta.isWritable)
            }
        }
        
        // Add program ID (always readonly non-signer)
        val programKey = instruction.programId.toBase58()
        if (!accountMap.containsKey(programKey)) {
            accountMap[programKey] = SortedAccountMeta(instruction.programId, isSigner = false, isWritable = false)
        }
        
        // Sort according to Solana rules:
        // 1. Writable signers
        // 2. Readonly signers  
        // 3. Writable non-signers
        // 4. Readonly non-signers
        return accountMap.values.sortedWith(compareBy(
            { !it.isSigner },           // Signers first
            { !it.isWritable },         // Writable first within each group
            { it.pubkey.toBase58() }    // Stable sort by address
        ))
    }
    
    /**
     * Build transaction message with proper Solana serialization.
     */
    private fun buildMessage(
        blockhash: String,
        sortedAccounts: List<SortedAccountMeta>,
        instruction: SolarmaInstruction
    ): ByteArray {
        // Count signers and read-only accounts from sorted list
        var numSigners = 0
        var numReadOnlySigners = 0
        var numReadOnlyNonSigners = 0
        
        for (acc in sortedAccounts) {
            if (acc.isSigner) {
                numSigners++
                if (!acc.isWritable) numReadOnlySigners++
            } else {
                if (!acc.isWritable) numReadOnlyNonSigners++
            }
        }
        
        val buffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        
        // Message Header: 3 bytes
        // [num_required_signatures, num_readonly_signed_accounts, num_readonly_unsigned_accounts]
        buffer.put(numSigners.toByte())
        buffer.put(numReadOnlySigners.toByte())
        buffer.put(numReadOnlyNonSigners.toByte())
        
        // Account keys array with compact-u16 length
        val accountKeys = sortedAccounts.map { it.pubkey }
        writeCompactU16(buffer, accountKeys.size)
        for (key in accountKeys) {
            buffer.put(key.bytes())
        }
        
        // Recent blockhash (32 bytes)
        val blockhashBytes = decodeBase58(blockhash)
        buffer.put(blockhashBytes)
        
        // Instructions array with compact-u16 length
        writeCompactU16(buffer, 1) // 1 instruction
        
        // Compiled instruction:
        // - program_id_index: u8
        // - accounts: compact-u16 length + u8[] indices
        // - data: compact-u16 length + u8[] data
        
        val programIndex = accountKeys.indexOfFirst { it.toBase58() == instruction.programId.toBase58() }
        buffer.put(programIndex.toByte())
        
        // Account indices with compact-u16 length
        writeCompactU16(buffer, instruction.accounts.size)
        for (meta in instruction.accounts) {
            val idx = accountKeys.indexOfFirst { it.toBase58() == meta.pubkey.toBase58() }
            buffer.put(idx.toByte())
        }
        
        // Instruction data with compact-u16 length
        writeCompactU16(buffer, instruction.data.size)
        buffer.put(instruction.data)
        
        // Return trimmed array
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }
    
    /**
     * Write a compact-u16 encoded integer.
     * Solana uses this for variable-length encoding.
     */
    private fun writeCompactU16(buffer: ByteBuffer, value: Int) {
        var rem = value
        while (true) {
            var byte = rem and 0x7f
            rem = rem shr 7
            if (rem == 0) {
                buffer.put(byte.toByte())
                break
            } else {
                byte = byte or 0x80
                buffer.put(byte.toByte())
            }
        }
    }
    
    /**
     * Build unsigned transaction for MWA.
     * MWA signAndSendTransactions expects full transaction with signature placeholder.
     * Format: [1 byte: num_signatures] + [64 bytes per signature] + [message]
     */
    private fun buildUnsignedTransaction(message: ByteArray): ByteArray {
        Log.d(TAG, "Message size: ${message.size} bytes")
        Log.d(TAG, "Message header: ${message.take(10).joinToString(" ") { "%02x".format(it) }}")
        
        // Transaction format: [sig_count] + [signatures...] + [message]
        // sig_count = 1 (compact-u16, fits in 1 byte for values < 128)
        // signature = 64 bytes of zeros (wallet will fill in)
        val tx = ByteBuffer.allocate(1 + 64 + message.size)
        tx.put(0x01.toByte())  // 1 signature required
        tx.put(ByteArray(64))   // Empty signature placeholder
        tx.put(message)
        
        val result = tx.array()
        Log.d(TAG, "Full TX size: ${result.size} bytes (1 + 64 + ${message.size})")
        Log.d(TAG, "TX start: ${result.take(10).joinToString(" ") { "%02x".format(it) }}")
        return result
    }
    
    /**
     * Decode Base58 string to 32 bytes.
     * Uses sol4k's PublicKey decoder which correctly handles leading zero bytes
     * (Base58 "1" prefix). The previous BigInteger-based implementation dropped
     * leading zeros, causing ~1/256 blockhashes to decode incorrectly.
     */
    private fun decodeBase58(input: String): ByteArray {
        return PublicKey(input).bytes()
    }
}

/**
 * Penalty route enum matching Anchor.
 * DONATE sends to project treasury for development.
 */
enum class PenaltyRoute(val code: Byte) {
    BURN(0),
    DONATE(1),  // Project treasury for Solarma development
    BUDDY(2)
    ;

    companion object {
        fun fromCode(code: Int): PenaltyRoute {
            return when (code) {
                1 -> DONATE
                2 -> BUDDY
                else -> BURN
            }
        }
    }
}

/**
 * Solarma project treasury for DONATE penalty route.
 */
object SolarmaTreasury {
    const val ADDRESS = "2g536HdC3ByKNZc6gev6jb6NmFr7dMnTnp66Gqk5rusk"
}
