package app.solarma.wallet

import android.util.Log
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builder for Solarma Anchor program instructions.
 * Constructs serialized transactions for MWA signing.
 */
@Singleton
class SolarmaInstructionBuilder
    @Inject
    constructor() {
        companion object {
            private const val TAG = "Solarma.TxBuilder"

            // Program ID - matches declare_id! in lib.rs
            val PROGRAM_ID = PublicKey("F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP")

            // Anchor discriminators (first 8 bytes of sha256("global:<instruction_name>"))
            private val DISCRIMINATOR_CREATE_ALARM =
                byteArrayOf(
                    0xdd.toByte(), 0x93.toByte(), 0xba.toByte(), 0xee.toByte(),
                    0xce.toByte(), 0x9b.toByte(), 0x33.toByte(), 0xa8.toByte(),
                )
            private val DISCRIMINATOR_CLAIM =
                byteArrayOf(
                    0x3e.toByte(), 0xc6.toByte(), 0xd6.toByte(), 0xc1.toByte(),
                    0xd5.toByte(), 0x9f.toByte(), 0x6c.toByte(), 0xd2.toByte(),
                )
            private val DISCRIMINATOR_SNOOZE =
                byteArrayOf(
                    0x15.toByte(), 0x02.toByte(), 0xbd.toByte(), 0x71.toByte(),
                    0x56.toByte(), 0x94.toByte(), 0x3a.toByte(), 0x0f.toByte(),
                )
            private val DISCRIMINATOR_SLASH =
                byteArrayOf(
                    0xcc.toByte(), 0x8d.toByte(), 0x12.toByte(), 0xa1.toByte(),
                    0x08.toByte(), 0xb1.toByte(), 0x5c.toByte(), 0x8e.toByte(),
                )
            private val DISCRIMINATOR_EMERGENCY_REFUND =
                byteArrayOf(
                    0xbc.toByte(), 0x49.toByte(), 0x34.toByte(), 0xc3.toByte(),
                    0x89.toByte(), 0x46.toByte(), 0xb4.toByte(), 0x93.toByte(),
                )

            // H3: sha256("global:ack_awake")[0..8]
            private val DISCRIMINATOR_ACK_AWAKE =
                byteArrayOf(
                    0xd4.toByte(), 0xca.toByte(), 0x3e.toByte(), 0x92.toByte(),
                    0x0f.toByte(), 0xb7.toByte(), 0xce.toByte(), 0x40.toByte(),
                )
        }

        /**
         * Build create_alarm instruction data and accounts.
         */
        fun buildCreateAlarm(
            owner: PublicKey,
            alarmId: Long,
            alarmTime: Long,
            deadline: Long,
            depositLamports: Long,
            penaltyRoute: Byte,
            penaltyDestination: PublicKey? = null,
        ): SolarmaInstruction {
            // Derive PDAs
            val alarmPda = deriveAlarmPda(owner, alarmId)
            val vaultPda = deriveVaultPda(alarmPda.address)

            // Log all parameters
            Log.d(TAG, "Building create_alarm:")
            Log.d(TAG, "  alarmId=$alarmId")
            Log.d(TAG, "  alarmTime=$alarmTime (${java.util.Date(alarmTime * 1000)})")
            Log.d(TAG, "  deadline=$deadline (${java.util.Date(deadline * 1000)})")
            Log.d(TAG, "  deposit=$depositLamports lamports (${depositLamports / 1_000_000_000.0} SOL)")
            Log.d(TAG, "  penaltyRoute=$penaltyRoute")
            Log.d(TAG, "  alarm PDA=${alarmPda.address.toBase58()}")
            Log.d(TAG, "  vault PDA=${vaultPda.address.toBase58()}")

            // Serialize instruction data
            val dataSize = 8 + 8 + 8 + 8 + 8 + 1 + 1 + (if (penaltyDestination != null) 32 else 0)
            val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(DISCRIMINATOR_CREATE_ALARM)
            buffer.putLong(alarmId)
            buffer.putLong(alarmTime)
            buffer.putLong(deadline)
            buffer.putLong(depositLamports)
            buffer.put(penaltyRoute)
            if (penaltyDestination != null) {
                buffer.put(1.toByte()) // Some
                buffer.put(penaltyDestination.bytes())
            } else {
                buffer.put(0.toByte()) // None
            }

            // Log instruction data hex
            val dataBytes = buffer.array()
            Log.d(TAG, "  Instruction data (${dataBytes.size} bytes): ${dataBytes.joinToString("") { "%02x".format(it) }}")

            // Build account keys
            val keys =
                listOf(
                    AccountMeta(alarmPda.address, isSigner = false, isWritable = true),
                    AccountMeta(vaultPda.address, isSigner = false, isWritable = true),
                    AccountMeta(owner, isSigner = true, isWritable = true),
                    AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                )

            return SolarmaInstruction(PROGRAM_ID, keys, buffer.array())
        }

        /**
         * Build claim instruction data and accounts.
         */
        fun buildClaim(
            owner: PublicKey,
            alarmPda: PublicKey,
        ): SolarmaInstruction {
            val vaultPda = deriveVaultPda(alarmPda)

            Log.d(TAG, "Building claim: alarm=${alarmPda.toBase58()}, vault=${vaultPda.address.toBase58()}")

            val keys =
                listOf(
                    AccountMeta(alarmPda, isSigner = false, isWritable = true),
                    AccountMeta(vaultPda.address, isSigner = false, isWritable = true),
                    AccountMeta(owner, isSigner = true, isWritable = true),
                    AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                )

            return SolarmaInstruction(PROGRAM_ID, keys, DISCRIMINATOR_CLAIM)
        }

        /**
         * H3: Build ack_awake instruction — records wake proof completion on-chain.
         * Only needs alarm PDA + owner signer (no vault, no system program).
         */
        fun buildAckAwake(
            owner: PublicKey,
            alarmPda: PublicKey,
        ): SolarmaInstruction {
            Log.d(TAG, "Building ack_awake: alarm=${alarmPda.toBase58()}")

            val keys =
                listOf(
                    AccountMeta(alarmPda, isSigner = false, isWritable = true),
                    AccountMeta(owner, isSigner = true, isWritable = true),
                )

            return SolarmaInstruction(PROGRAM_ID, keys, DISCRIMINATOR_ACK_AWAKE)
        }

        /**
         * Build snooze instruction data and accounts.
         * @param expectedSnoozeCount current snooze count for idempotency guard
         */
        fun buildSnooze(
            owner: PublicKey,
            alarmPda: PublicKey,
            sinkAddress: PublicKey,
            expectedSnoozeCount: Int,
        ): SolarmaInstruction {
            val vaultPda = deriveVaultPda(alarmPda)

            Log.d(
                TAG,
                "Building snooze: alarm=${alarmPda.toBase58()}, " +
                    "vault=${vaultPda.address.toBase58()}, " +
                    "sink=${sinkAddress.toBase58()}, " +
                    "expectedCount=$expectedSnoozeCount",
            )

            val keys =
                listOf(
                    AccountMeta(alarmPda, isSigner = false, isWritable = true),
                    AccountMeta(vaultPda.address, isSigner = false, isWritable = true),
                    AccountMeta(sinkAddress, isSigner = false, isWritable = true),
                    AccountMeta(owner, isSigner = true, isWritable = true),
                    AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                )

            // Instruction data: 8-byte discriminator + 1-byte expected_snooze_count
            val data = DISCRIMINATOR_SNOOZE + byteArrayOf(expectedSnoozeCount.toByte())
            return SolarmaInstruction(PROGRAM_ID, keys, data)
        }

        /**
         * Build emergency_refund instruction data and accounts.
         */
        fun buildEmergencyRefund(
            owner: PublicKey,
            alarmPda: PublicKey,
            sinkAddress: PublicKey,
        ): SolarmaInstruction {
            val vaultPda = deriveVaultPda(alarmPda)

            Log.d(
                TAG,
                "Building emergency_refund: alarm=${alarmPda.toBase58()}, " +
                    "vault=${vaultPda.address.toBase58()}, " +
                    "sink=${sinkAddress.toBase58()}",
            )

            val keys =
                listOf(
                    AccountMeta(alarmPda, isSigner = false, isWritable = true),
                    AccountMeta(vaultPda.address, isSigner = false, isWritable = true),
                    AccountMeta(sinkAddress, isSigner = false, isWritable = true),
                    AccountMeta(owner, isSigner = true, isWritable = true),
                    AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                )

            return SolarmaInstruction(PROGRAM_ID, keys, DISCRIMINATOR_EMERGENCY_REFUND)
        }

        /**
         * Build slash instruction data and accounts.
         */
        fun buildSlash(
            caller: PublicKey,
            alarmPda: PublicKey,
            penaltyRecipient: PublicKey,
        ): SolarmaInstruction {
            val vaultPda = deriveVaultPda(alarmPda)

            Log.d(
                TAG,
                "Building slash: alarm=${alarmPda.toBase58()}, " +
                    "vault=${vaultPda.address.toBase58()}, " +
                    "recipient=${penaltyRecipient.toBase58()}",
            )

            val keys =
                listOf(
                    AccountMeta(alarmPda, isSigner = false, isWritable = true),
                    AccountMeta(vaultPda.address, isSigner = false, isWritable = true),
                    AccountMeta(penaltyRecipient, isSigner = false, isWritable = true),
                    AccountMeta(caller, isSigner = true, isWritable = true),
                    AccountMeta(SYSTEM_PROGRAM_ID, isSigner = false, isWritable = false),
                )

            return SolarmaInstruction(PROGRAM_ID, keys, DISCRIMINATOR_SLASH)
        }

        /**
         * Derive alarm PDA from owner and alarm_id.
         * Uses proper on-curve check for Solana PDA derivation.
         */
        fun deriveAlarmPda(
            owner: PublicKey,
            alarmId: Long,
        ): PdaResult {
            val idBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(alarmId).array()
            val seeds = listOf("alarm".toByteArray(), owner.bytes(), idBytes)
            return findProgramDerivedAddress(seeds, PROGRAM_ID)
        }

        /**
         * Derive vault PDA from alarm PDA.
         */
        fun deriveVaultPda(alarmPda: PublicKey): PdaResult {
            val seeds = listOf("vault".toByteArray(), alarmPda.bytes())
            return findProgramDerivedAddress(seeds, PROGRAM_ID)
        }

        /**
         * Find program derived address with proper on-curve check.
         * Matches Solana's find_program_address algorithm.
         */
        private fun findProgramDerivedAddress(
            seeds: List<ByteArray>,
            programId: PublicKey,
        ): PdaResult {
            for (bump in 255 downTo 0) {
                try {
                    val seedsWithBump = seeds + byteArrayOf(bump.toByte())
                    val address = createProgramAddress(seedsWithBump, programId)
                    return PdaResult(address, bump.toByte())
                } catch (e: Exception) {
                    // Continue if on-curve
                }
            }
            throw RuntimeException("Unable to find valid PDA")
        }

        /**
         * Create program address from seeds.
         * Throws if resulting address is on the Ed25519 curve.
         */
        private fun createProgramAddress(
            seeds: List<ByteArray>,
            programId: PublicKey,
        ): PublicKey {
            val label = "ProgramDerivedAddress".toByteArray()
            val totalSize = seeds.sumOf { it.size } + programId.bytes().size + label.size
            val buffer = java.nio.ByteBuffer.allocate(totalSize)
            seeds.forEach { buffer.put(it) }
            buffer.put(programId.bytes())
            buffer.put(label)

            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(buffer.array())

            // Check if on Ed25519 curve - if yes, this is NOT a valid PDA
            if (isOnCurve(hash)) {
                throw IllegalArgumentException("Address is on curve, invalid PDA")
            }
            return PublicKey(hash)
        }

        /**
         * Check if point is on Ed25519 curve using TweetNaCl.
         * A valid PDA must NOT be on the curve.
         */
        private fun isOnCurve(bytes: ByteArray): Boolean {
            return org.sol4k.tweetnacl.TweetNaclFast.isOnCurve(bytes)
        }
    }

/**
 * System program ID.
 */
val SYSTEM_PROGRAM_ID = PublicKey("11111111111111111111111111111111")

/**
 * Program Derived Address result.
 */
data class PdaResult(
    val address: PublicKey,
    val bump: Byte,
)

/**
 * Account meta for instructions.
 */
data class AccountMeta(
    val pubkey: PublicKey,
    val isSigner: Boolean,
    val isWritable: Boolean,
)

/**
 * Solarma instruction data.
 */
data class SolarmaInstruction(
    val programId: PublicKey,
    val accounts: List<AccountMeta>,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SolarmaInstruction) return false
        return programId == other.programId &&
            accounts == other.accounts &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = programId.hashCode()
        result = 31 * result + accounts.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
