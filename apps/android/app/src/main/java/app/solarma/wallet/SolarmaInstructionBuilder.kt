package app.solarma.wallet

import android.util.Log
import org.sol4k.*
import org.sol4k.instruction.Instruction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builder for Solarma Anchor program instructions.
 * Constructs serialized transactions for MWA signing.
 */
@Singleton
class SolarmaInstructionBuilder @Inject constructor() {
    
    companion object {
        private const val TAG = "Solarma.TxBuilder"
        
        // Program ID - matches declare_id! in lib.rs
        val PROGRAM_ID = PublicKey("So1armaVau1t1111111111111111111111111111111")
        
        // Anchor discriminators (first 8 bytes of sha256("global:<instruction_name>"))
        private val DISCRIMINATOR_CREATE_ALARM = byteArrayOf(
            0x18.toByte(), 0x9d.toByte(), 0x67.toByte(), 0xa5.toByte(),
            0xc6.toByte(), 0x1a.toByte(), 0x37.toByte(), 0x1c.toByte()
        )
        private val DISCRIMINATOR_CLAIM = byteArrayOf(
            0x3e.toByte(), 0xc6.toByte(), 0xd6.toByte(), 0xc1.toByte(),
            0xd5.toByte(), 0x9d.toByte(), 0xf5.toByte(), 0x8d.toByte()
        )
        private val DISCRIMINATOR_SNOOZE = byteArrayOf(
            0xb4.toByte(), 0x9e.toByte(), 0x2f.toByte(), 0xb8.toByte(),
            0x79.toByte(), 0xd0.toByte(), 0x6e.toByte(), 0xa2.toByte()
        )
        private val DISCRIMINATOR_EMERGENCY_REFUND = byteArrayOf(
            0x5f.toByte(), 0xc2.toByte(), 0x3d.toByte(), 0x8c.toByte(),
            0x1a.toByte(), 0xe3.toByte(), 0x4b.toByte(), 0x92.toByte()
        )
    }
    
    /**
     * Build create_alarm instruction.
     */
    fun buildCreateAlarm(
        owner: PublicKey,
        alarmId: Long,
        alarmTime: Long,
        deadline: Long,
        depositLamports: Long,
        penaltyRoute: Byte,
        penaltyDestination: PublicKey? = null
    ): Instruction {
        // Derive PDAs
        val (alarmPda, alarmBump) = deriveAlarmPda(owner, alarmId)
        val (vaultPda, vaultBump) = deriveVaultPda(alarmPda)
        
        Log.d(TAG, "Building create_alarm: alarm=$alarmPda, vault=$vaultPda")
        
        // Serialize instruction data
        val data = ByteBuffer.allocate(8 + 8 + 8 + 8 + 8 + 1 + 1 + 32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(DISCRIMINATOR_CREATE_ALARM)
            .putLong(alarmId)
            .putLong(alarmTime)
            .putLong(deadline)
            .putLong(depositLamports)
            .put(penaltyRoute)
            .apply {
                if (penaltyDestination != null) {
                    put(1) // Some
                    put(penaltyDestination.bytes())
                } else {
                    put(0) // None
                }
            }
            .array()
        
        // Build account keys
        val keys = listOf(
            AccountMeta(alarmPda, isSigner = false, isWritable = true),
            AccountMeta(vaultPda, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = true),
            AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false)
        )
        
        return Instruction(PROGRAM_ID, keys, data)
    }
    
    /**
     * Build claim instruction.
     */
    fun buildClaim(
        owner: PublicKey,
        alarmPda: PublicKey,
        vaultBump: Byte
    ): Instruction {
        val (vaultPda, _) = deriveVaultPda(alarmPda)
        
        Log.d(TAG, "Building claim: alarm=$alarmPda, vault=$vaultPda")
        
        // Serialize instruction data (just discriminator)
        val data = DISCRIMINATOR_CLAIM
        
        val keys = listOf(
            AccountMeta(alarmPda, isSigner = false, isWritable = true),
            AccountMeta(vaultPda, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = true),
            AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false)
        )
        
        return Instruction(PROGRAM_ID, keys, data)
    }
    
    /**
     * Build snooze instruction.
     */
    fun buildSnooze(
        owner: PublicKey,
        alarmPda: PublicKey,
        sinkAddress: PublicKey
    ): Instruction {
        val (vaultPda, _) = deriveVaultPda(alarmPda)
        
        Log.d(TAG, "Building snooze: alarm=$alarmPda, vault=$vaultPda, sink=$sinkAddress")
        
        val data = DISCRIMINATOR_SNOOZE
        
        val keys = listOf(
            AccountMeta(alarmPda, isSigner = false, isWritable = true),
            AccountMeta(vaultPda, isSigner = false, isWritable = true),
            AccountMeta(sinkAddress, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = true),
            AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false)
        )
        
        return Instruction(PROGRAM_ID, keys, data)
    }
    
    /**
     * Build emergency_refund instruction.
     */
    fun buildEmergencyRefund(
        owner: PublicKey,
        alarmPda: PublicKey
    ): Instruction {
        val (vaultPda, _) = deriveVaultPda(alarmPda)
        
        Log.d(TAG, "Building emergency_refund: alarm=$alarmPda, vault=$vaultPda")
        
        val data = DISCRIMINATOR_EMERGENCY_REFUND
        
        val keys = listOf(
            AccountMeta(alarmPda, isSigner = false, isWritable = true),
            AccountMeta(vaultPda, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = true),
            AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false)
        )
        
        return Instruction(PROGRAM_ID, keys, data)
    }
    
    /**
     * Derive alarm PDA from owner and alarm_id.
     */
    fun deriveAlarmPda(owner: PublicKey, alarmId: Long): Pair<PublicKey, Byte> {
        val seeds = listOf(
            "alarm".toByteArray(),
            owner.bytes(),
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(alarmId).array()
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID)
    }
    
    /**
     * Derive vault PDA from alarm PDA.
     */
    fun deriveVaultPda(alarmPda: PublicKey): Pair<PublicKey, Byte> {
        val seeds = listOf(
            "vault".toByteArray(),
            alarmPda.bytes()
        )
        return PublicKey.findProgramAddress(seeds, PROGRAM_ID)
    }
}

/**
 * System program constants.
 */
object SystemProgram {
    val PROGRAM_ID = PublicKey("11111111111111111111111111111111")
}

/**
 * Account meta for instructions.
 */
data class AccountMeta(
    val pubkey: PublicKey,
    val isSigner: Boolean,
    val isWritable: Boolean
)
