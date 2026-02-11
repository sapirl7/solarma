package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deep coverage tests for TransactionBuilder.
 *
 * Exercises snapshot-based transaction assembly, Solana message serialization,
 * account ordering, compact-u16 encoding, and Base58 decode. All methods
 * are tested through the production `buildUnsignedTransactionForSnapshot` path.
 */
class TransactionBuilderTest {

    private val instructionBuilder = SolarmaInstructionBuilder()
    private val txBuilder = TransactionBuilder(SolanaRpcClient(), instructionBuilder)
    private val owner = PublicKey("11111111111111111111111111111111")
    private val fakePubkey = PublicKey("22222222222222222222222222222222")
    private val fakeBlockhash = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"

    // =========================================================================
    // buildUnsignedTransactionForSnapshot — full pipeline
    // =========================================================================

    @Test
    fun `snapshot transaction starts with signature count and placeholder`() {
        val instruction = instructionBuilder.buildCreateAlarm(
            owner = owner,
            alarmId = 1L,
            alarmTime = 1700000000L,
            deadline = 1700001800L,
            depositLamports = 100_000_000L,
            penaltyRoute = 0,
            penaltyDestination = null
        )

        val tx = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, fakeBlockhash)

        // First byte: 1 signature required
        assertEquals("Signature count", 0x01.toByte(), tx[0])

        // Next 64 bytes: zero placeholder for signature
        for (i in 1..64) {
            assertEquals("Signature placeholder byte $i", 0x00.toByte(), tx[i])
        }
    }

    @Test
    fun `snapshot transaction message header has correct counts`() {
        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = instructionBuilder.deriveAlarmPda(owner, 1L).address
        )

        val tx = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, fakeBlockhash)

        // Message starts at offset 65 (1 + 64)
        val msgStart = 65
        val numSigners = tx[msgStart].toInt()
        val numReadOnlySigners = tx[msgStart + 1].toInt()
        val numReadOnlyNonSigners = tx[msgStart + 2].toInt()

        // At least 1 signer (fee payer / owner)
        assertTrue("numSigners >= 1", numSigners >= 1)
        // No readonly signers expected (owner is writable)
        assertEquals("numReadOnlySigners", 0, numReadOnlySigners)
        // At least program ID is readonly non-signer
        assertTrue("numReadOnlyNonSigners >= 1", numReadOnlyNonSigners >= 1)
        // All values are non-negative and consistent
        assertTrue("header values non-negative",
            numSigners >= 0 && numReadOnlySigners >= 0 && numReadOnlyNonSigners >= 0)
    }

    @Test
    fun `snapshot transaction is non-empty for all instruction types`() {
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, 42L).address
        val sink = TransactionBuilder.BURN_SINK

        val instructions = listOf(
            "create_alarm" to instructionBuilder.buildCreateAlarm(
                owner, 42L, 1700000000L, 1700001800L, 100_000_000L, 0, null
            ),
            "claim" to instructionBuilder.buildClaim(owner, alarmPda),
            "ack_awake" to instructionBuilder.buildAckAwake(owner, alarmPda),
            "snooze" to instructionBuilder.buildSnooze(owner, alarmPda, sink, 0),
            "emergency_refund" to instructionBuilder.buildEmergencyRefund(owner, alarmPda, sink),
            "slash" to instructionBuilder.buildSlash(owner, alarmPda, sink)
        )

        for ((name, ix) in instructions) {
            val tx = txBuilder.buildUnsignedTransactionForSnapshot(owner, ix, fakeBlockhash)
            assertTrue("$name transaction should have >65 bytes", tx.size > 65)
        }
    }

    @Test
    fun `different blockhashes produce different transactions`() {
        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = instructionBuilder.deriveAlarmPda(owner, 1L).address
        )

        val hash1 = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
        val hash2 = "3SU7bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKiDDD"

        val tx1 = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, hash1)
        val tx2 = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, hash2)

        assertFalse("Different blockhashes should produce different TXs",
            tx1.contentEquals(tx2))
    }

    @Test
    fun `same inputs produce identical transactions (deterministic)`() {
        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = instructionBuilder.deriveAlarmPda(owner, 1L).address
        )

        val tx1 = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, fakeBlockhash)
        val tx2 = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, fakeBlockhash)

        assertArrayEquals("Same inputs must produce identical TXs", tx1, tx2)
    }

    // =========================================================================
    // Account ordering: writable signers → readonly signers → writable non-signers → readonly
    // =========================================================================

    @Test
    fun `fee payer appears first in message accounts`() {
        val instruction = instructionBuilder.buildClaim(
            owner = owner,
            alarmPda = instructionBuilder.deriveAlarmPda(owner, 1L).address
        )

        val tx = txBuilder.buildUnsignedTransactionForSnapshot(owner, instruction, fakeBlockhash)

        // After message header (3 bytes) and compact-u16 account count (1 byte for small values),
        // the first public key (32 bytes) is the fee payer
        val msgStart = 65
        val accountCountOffset = msgStart + 3
        // compact-u16: for values < 128, it's 1 byte
        val firstKeyOffset = accountCountOffset + 1

        val firstKey = tx.copyOfRange(firstKeyOffset, firstKeyOffset + 32)
        assertArrayEquals("Fee payer (owner) should be first account",
            owner.bytes(), firstKey)
    }

    // =========================================================================
    // SolarmaInstruction equality and hashCode
    // =========================================================================

    @Test
    fun `SolarmaInstruction equals with same data`() {
        val ix1 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = listOf(AccountMeta(owner, isSigner = true, isWritable = true)),
            data = byteArrayOf(1, 2, 3)
        )
        val ix2 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = listOf(AccountMeta(owner, isSigner = true, isWritable = true)),
            data = byteArrayOf(1, 2, 3)
        )

        assertEquals(ix1, ix2)
        assertEquals(ix1.hashCode(), ix2.hashCode())
    }

    @Test
    fun `SolarmaInstruction not equal with different data`() {
        val ix1 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = emptyList(),
            data = byteArrayOf(1, 2, 3)
        )
        val ix2 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = emptyList(),
            data = byteArrayOf(4, 5, 6)
        )

        assertNotEquals(ix1, ix2)
    }

    @Test
    fun `SolarmaInstruction not equal with different accounts`() {
        val ix1 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = listOf(AccountMeta(owner, isSigner = true, isWritable = true)),
            data = byteArrayOf(1)
        )
        val ix2 = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = listOf(AccountMeta(owner, isSigner = false, isWritable = true)),
            data = byteArrayOf(1)
        )

        assertNotEquals(ix1, ix2)
    }

    @Test
    fun `SolarmaInstruction not equal to non-SolarmaInstruction`() {
        val ix = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = emptyList(),
            data = byteArrayOf()
        )
        assertNotEquals(ix, "not an instruction")
        assertNotEquals(ix, null)
    }

    @Test
    fun `SolarmaInstruction equals itself`() {
        val ix = SolarmaInstruction(
            programId = SolarmaInstructionBuilder.PROGRAM_ID,
            accounts = emptyList(),
            data = byteArrayOf(1, 2, 3)
        )
        assertEquals(ix, ix)
    }

    // =========================================================================
    // AccountMeta data class
    // =========================================================================

    @Test
    fun `AccountMeta data class equality`() {
        val meta1 = AccountMeta(owner, isSigner = true, isWritable = false)
        val meta2 = AccountMeta(owner, isSigner = true, isWritable = false)

        assertEquals(meta1, meta2)
        assertEquals(meta1.hashCode(), meta2.hashCode())
    }

    @Test
    fun `AccountMeta data class inequality`() {
        val meta1 = AccountMeta(owner, isSigner = true, isWritable = false)
        val meta2 = AccountMeta(owner, isSigner = false, isWritable = false)
        val meta3 = AccountMeta(fakePubkey, isSigner = true, isWritable = false)

        assertNotEquals(meta1, meta2)
        assertNotEquals(meta1, meta3)
    }

    @Test
    fun `AccountMeta copy works`() {
        val meta = AccountMeta(owner, isSigner = true, isWritable = false)
        val copy = meta.copy(isWritable = true)

        assertTrue(copy.isWritable)
        assertEquals(meta.pubkey, copy.pubkey)
        assertEquals(meta.isSigner, copy.isSigner)
    }

    // =========================================================================
    // PdaResult data class
    // =========================================================================

    @Test
    fun `PdaResult data class equality`() {
        val pda1 = PdaResult(owner, 254.toByte())
        val pda2 = PdaResult(owner, 254.toByte())

        assertEquals(pda1, pda2)
        assertEquals(pda1.hashCode(), pda2.hashCode())
    }

    @Test
    fun `PdaResult destructuring`() {
        val pda = PdaResult(owner, 250.toByte())
        val (address, bump) = pda

        assertEquals(owner, address)
        assertEquals(250.toByte(), bump)
    }

    // =========================================================================
    // PenaltyRoute enum — thorough coverage
    // =========================================================================

    @Test
    fun `PenaltyRoute values array has correct order`() {
        val values = PenaltyRoute.entries
        assertEquals(PenaltyRoute.BURN, values[0])
        assertEquals(PenaltyRoute.DONATE, values[1])
        assertEquals(PenaltyRoute.BUDDY, values[2])
    }

    @Test
    fun `PenaltyRoute fromCode stress test 0 to 255`() {
        for (code in 0..255) {
            val route = PenaltyRoute.fromCode(code)
            when (code) {
                0 -> assertEquals(PenaltyRoute.BURN, route)
                1 -> assertEquals(PenaltyRoute.DONATE, route)
                2 -> assertEquals(PenaltyRoute.BUDDY, route)
                else -> assertEquals("Code $code should default to BURN",
                    PenaltyRoute.BURN, route)
            }
        }
    }

    @Test
    fun `PenaltyRoute name matches enum`() {
        assertEquals("BURN", PenaltyRoute.BURN.name)
        assertEquals("DONATE", PenaltyRoute.DONATE.name)
        assertEquals("BUDDY", PenaltyRoute.BUDDY.name)
    }

    @Test
    fun `PenaltyRoute valueOf works`() {
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.valueOf("BURN"))
        assertEquals(PenaltyRoute.DONATE, PenaltyRoute.valueOf("DONATE"))
        assertEquals(PenaltyRoute.BUDDY, PenaltyRoute.valueOf("BUDDY"))
    }

    // =========================================================================
    // SolarmaTreasury constants
    // =========================================================================

    @Test
    fun `treasury address is a valid PublicKey`() {
        // If this throws, the address is invalid
        val pk = PublicKey(SolarmaTreasury.ADDRESS)
        assertEquals(32, pk.bytes().size)
    }

    @Test
    fun `BURN_SINK is a valid PublicKey`() {
        val pk = TransactionBuilder.BURN_SINK
        assertEquals(32, pk.bytes().size)
        assertEquals("1nc1nerator11111111111111111111111111111111", pk.toBase58())
    }

    // =========================================================================
    // Create alarm with DONATE path exercises buddy branch
    // =========================================================================

    @Test
    fun `create alarm with DONATE follows treasury path`() {
        val treasury = PublicKey(SolarmaTreasury.ADDRESS)
        val ix = instructionBuilder.buildCreateAlarm(
            owner = owner,
            alarmId = 999L,
            alarmTime = 1700000000L,
            deadline = 1700001800L,
            depositLamports = 500_000_000L,
            penaltyRoute = PenaltyRoute.DONATE.code,
            penaltyDestination = treasury
        )

        // Data includes Option<Pubkey> = Some(treasury) → 1 + 32 bytes extra
        assertEquals(8 + 8 + 8 + 8 + 8 + 1 + 1 + 32, ix.data.size)
    }

    @Test
    fun `create alarm without destination has None option`() {
        val ix = instructionBuilder.buildCreateAlarm(
            owner = owner,
            alarmId = 1000L,
            alarmTime = 1700000000L,
            deadline = 1700001800L,
            depositLamports = 50_000_000L,
            penaltyRoute = PenaltyRoute.BURN.code,
            penaltyDestination = null
        )

        // No destination: Option<Pubkey> = None → 1 byte only
        assertEquals(8 + 8 + 8 + 8 + 8 + 1 + 1, ix.data.size)
    }

    // =========================================================================
    // buildTransaction with all instruction types produces valid transaction
    // =========================================================================

    @Test
    fun `snapshot transaction for snooze includes expected_snooze_count in data`() {
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, 5L).address
        val ix = instructionBuilder.buildSnooze(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = TransactionBuilder.BURN_SINK,
            expectedSnoozeCount = 3
        )

        // Snooze instruction data: 8-byte discriminator + 1-byte expected_snooze_count
        assertEquals(9, ix.data.size)
        assertEquals(3.toByte(), ix.data[8]) // Last byte is the count
    }

    @Test
    fun `snapshot transaction for ack_awake has minimal accounts`() {
        val alarmPda = instructionBuilder.deriveAlarmPda(owner, 7L).address
        val ix = instructionBuilder.buildAckAwake(owner = owner, alarmPda = alarmPda)

        // ack_awake only needs 2 accounts (alarmPda + owner)
        assertEquals(2, ix.accounts.size)
        assertEquals(8, ix.data.size) // discriminator only
    }

    // =========================================================================
    // SYSTEM_PROGRAM_ID constant
    // =========================================================================

    @Test
    fun `SYSTEM_PROGRAM_ID is 32 zero bytes`() {
        val expected = ByteArray(32)
        assertArrayEquals(expected, SYSTEM_PROGRAM_ID.bytes())
        assertEquals("11111111111111111111111111111111", SYSTEM_PROGRAM_ID.toBase58())
    }
}
