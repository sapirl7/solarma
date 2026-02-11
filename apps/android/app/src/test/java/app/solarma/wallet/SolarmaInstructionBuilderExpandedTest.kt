package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Expanded coverage tests for SolarmaInstructionBuilder.
 *
 * Focuses on PDA derivation properties, discriminator hash verification,
 * instruction data layout, and account meta ordering for all instruction types.
 */
class SolarmaInstructionBuilderExpandedTest {

    private val builder = SolarmaInstructionBuilder()
    private val owner = PublicKey("11111111111111111111111111111111")

    // =========================================================================
    // PDA Derivation Properties
    // =========================================================================

    @Test
    fun `different alarmIds produce different PDAs`() {
        val pda1 = builder.deriveAlarmPda(owner, 1L)
        val pda2 = builder.deriveAlarmPda(owner, 2L)
        val pda3 = builder.deriveAlarmPda(owner, Long.MAX_VALUE)

        assertNotEquals(pda1.address.toBase58(), pda2.address.toBase58())
        assertNotEquals(pda1.address.toBase58(), pda3.address.toBase58())
        assertNotEquals(pda2.address.toBase58(), pda3.address.toBase58())
    }

    @Test
    fun `different owners produce different PDAs for same alarmId`() {
        val owner1 = PublicKey("11111111111111111111111111111111")
        val owner2 = PublicKey("22222222222222222222222222222222")

        val pda1 = builder.deriveAlarmPda(owner1, 100L)
        val pda2 = builder.deriveAlarmPda(owner2, 100L)

        assertNotEquals(pda1.address.toBase58(), pda2.address.toBase58())
    }

    @Test
    fun `alarm PDA bump is valid byte`() {
        for (id in listOf(0L, 1L, 42L, 1000L, Long.MAX_VALUE)) {
            val pda = builder.deriveAlarmPda(owner, id)
            val bump = pda.bump.toInt() and 0xFF
            assertTrue("Bump should be 0-255, got $bump", bump in 0..255)
        }
    }

    @Test
    fun `vault PDA is derived from alarm PDA not owner`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val vaultPda1 = builder.deriveVaultPda(alarmPda)

        // If we use a different alarm PDA, we should get a different vault
        val alarmPda2 = builder.deriveAlarmPda(owner, 2L).address
        val vaultPda2 = builder.deriveVaultPda(alarmPda2)

        assertNotEquals(vaultPda1.address.toBase58(), vaultPda2.address.toBase58())
    }

    @Test
    fun `PDA addresses are 32 bytes`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L)
        val vaultPda = builder.deriveVaultPda(alarmPda.address)

        assertEquals(32, alarmPda.address.bytes().size)
        assertEquals(32, vaultPda.address.bytes().size)
    }

    @Test
    fun `alarm PDA with zero alarmId is valid`() {
        val pda = builder.deriveAlarmPda(owner, 0L)
        assertNotNull(pda.address)
        assertEquals(32, pda.address.bytes().size)
    }

    @Test
    fun `alarm PDA with negative alarmId is valid`() {
        // Rust treats alarm_id as u64 (unsigned), but Java uses signed Long.
        // The PDA derivation should still work with any bit pattern.
        val pda = builder.deriveAlarmPda(owner, -1L)
        assertNotNull(pda.address)
        assertEquals(32, pda.address.bytes().size)
    }

    // =========================================================================
    // Discriminator Verification
    // =========================================================================

    @Test
    fun `create_alarm discriminator matches Anchor convention`() {
        val expected = sha256("global:create_alarm").copyOfRange(0, 8)
        val ix = builder.buildCreateAlarm(
            owner = owner, alarmId = 1L, alarmTime = 1L, deadline = 2L,
            depositLamports = 0L, penaltyRoute = 0
        )
        assertArrayEquals("create_alarm discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    @Test
    fun `claim discriminator matches Anchor convention`() {
        val expected = sha256("global:claim").copyOfRange(0, 8)
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val ix = builder.buildClaim(owner = owner, alarmPda = alarmPda)
        assertArrayEquals("claim discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    @Test
    fun `snooze discriminator matches Anchor convention`() {
        val expected = sha256("global:snooze").copyOfRange(0, 8)
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("1nc1nerator11111111111111111111111111111111")
        val ix = builder.buildSnooze(owner = owner, alarmPda = alarmPda, sinkAddress = sink, expectedSnoozeCount = 0)
        assertArrayEquals("snooze discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    @Test
    fun `slash discriminator matches Anchor convention`() {
        val expected = sha256("global:slash").copyOfRange(0, 8)
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val recipient = PublicKey("1nc1nerator11111111111111111111111111111111")
        val ix = builder.buildSlash(caller = owner, alarmPda = alarmPda, penaltyRecipient = recipient)
        assertArrayEquals("slash discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    @Test
    fun `emergency_refund discriminator matches Anchor convention`() {
        val expected = sha256("global:emergency_refund").copyOfRange(0, 8)
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("1nc1nerator11111111111111111111111111111111")
        val ix = builder.buildEmergencyRefund(owner = owner, alarmPda = alarmPda, sinkAddress = sink)
        assertArrayEquals("emergency_refund discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    @Test
    fun `ack_awake discriminator matches Anchor convention`() {
        val expected = sha256("global:ack_awake").copyOfRange(0, 8)
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val ix = builder.buildAckAwake(owner = owner, alarmPda = alarmPda)
        assertArrayEquals("ack_awake discriminator", expected, ix.data.copyOfRange(0, 8))
    }

    // =========================================================================
    // Instruction Data Layout Verification
    // =========================================================================

    @Test
    fun `create_alarm data encodes alarmId correctly`() {
        val alarmId = 0x0102030405060708L
        val ix = builder.buildCreateAlarm(
            owner = owner, alarmId = alarmId, alarmTime = 0L, deadline = 0L,
            depositLamports = 0L, penaltyRoute = 0
        )

        // Alarm ID is right after 8-byte discriminator, little-endian
        val buffer = ByteBuffer.wrap(ix.data, 8, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(alarmId, buffer.long)
    }

    @Test
    fun `create_alarm data encodes times correctly`() {
        val alarmTime = 1700000000L
        val deadline = 1700001800L
        val ix = builder.buildCreateAlarm(
            owner = owner, alarmId = 1L, alarmTime = alarmTime, deadline = deadline,
            depositLamports = 0L, penaltyRoute = 0
        )

        val buffer = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(16)  // skip discriminator (8) + alarmId (8)
        assertEquals(alarmTime, buffer.long)
        assertEquals(deadline, buffer.long)
    }

    @Test
    fun `create_alarm data encodes deposit correctly`() {
        val deposit = 5_000_000_000L  // 5 SOL
        val ix = builder.buildCreateAlarm(
            owner = owner, alarmId = 1L, alarmTime = 0L, deadline = 0L,
            depositLamports = deposit, penaltyRoute = 0
        )

        val buffer = ByteBuffer.wrap(ix.data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(32)  // skip disc(8) + id(8) + time(8) + deadline(8)
        assertEquals(deposit, buffer.long)
    }

    @Test
    fun `create_alarm penalty route byte is correct`() {
        for (route in PenaltyRoute.entries) {
            val ix = builder.buildCreateAlarm(
                owner = owner, alarmId = 1L, alarmTime = 0L, deadline = 0L,
                depositLamports = 0L, penaltyRoute = route.code
            )

            // Penalty route byte is at offset 40 (after disc(8) + id(8) + time(8) + deadline(8) + deposit(8))
            assertEquals("Route ${route.name}", route.code, ix.data[40])
        }
    }

    // =========================================================================
    // Account Meta Ordering Per Instruction
    // =========================================================================

    @Test
    fun `buildCreateAlarm accounts are alarm, vault, owner, system`() {
        val ix = builder.buildCreateAlarm(
            owner = owner, alarmId = 1L, alarmTime = 0L, deadline = 0L,
            depositLamports = 0L, penaltyRoute = 0
        )

        assertEquals(4, ix.accounts.size)
        // Alarm PDA
        assertFalse(ix.accounts[0].isSigner)
        assertTrue(ix.accounts[0].isWritable)
        // Vault PDA
        assertFalse(ix.accounts[1].isSigner)
        assertTrue(ix.accounts[1].isWritable)
        // Owner (signer)
        assertTrue(ix.accounts[2].isSigner)
        assertTrue(ix.accounts[2].isWritable)
        // System Program
        assertFalse(ix.accounts[3].isSigner)
        assertFalse(ix.accounts[3].isWritable)
    }

    @Test
    fun `buildClaim accounts are alarm, vault, owner, system`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val ix = builder.buildClaim(owner = owner, alarmPda = alarmPda)

        assertEquals(4, ix.accounts.size)
        assertTrue(ix.accounts[2].isSigner)
        assertFalse(ix.accounts[3].isWritable)  // system program
    }

    @Test
    fun `buildAckAwake has only 2 accounts`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val ix = builder.buildAckAwake(owner = owner, alarmPda = alarmPda)

        assertEquals(2, ix.accounts.size)
        // Alarm PDA
        assertFalse(ix.accounts[0].isSigner)
        assertTrue(ix.accounts[0].isWritable)
        // Owner
        assertTrue(ix.accounts[1].isSigner)
        assertTrue(ix.accounts[1].isWritable)
    }

    @Test
    fun `buildSnooze has 5 accounts with sink`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("22222222222222222222222222222222")
        val ix = builder.buildSnooze(owner = owner, alarmPda = alarmPda, sinkAddress = sink, expectedSnoozeCount = 5)

        assertEquals(5, ix.accounts.size)
        assertEquals(sink, ix.accounts[2].pubkey)  // sink address
        assertTrue(ix.accounts[2].isWritable)  // sink receives funds
    }

    @Test
    fun `buildEmergencyRefund has 5 accounts with sink`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("22222222222222222222222222222222")
        val ix = builder.buildEmergencyRefund(owner = owner, alarmPda = alarmPda, sinkAddress = sink)

        assertEquals(5, ix.accounts.size)
        assertEquals(sink, ix.accounts[2].pubkey)
    }

    @Test
    fun `buildSlash has 5 accounts with penalty recipient`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val recipient = PublicKey("33333333333333333333333333333333")
        val ix = builder.buildSlash(caller = owner, alarmPda = alarmPda, penaltyRecipient = recipient)

        assertEquals(5, ix.accounts.size)
        assertEquals(recipient, ix.accounts[2].pubkey)
    }

    // =========================================================================
    // All instructions use correct program ID
    // =========================================================================

    @Test
    fun `all instructions use PROGRAM_ID`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("1nc1nerator11111111111111111111111111111111")

        val instructions = listOf(
            builder.buildCreateAlarm(owner, 1L, 0L, 0L, 0L, 0),
            builder.buildClaim(owner, alarmPda),
            builder.buildAckAwake(owner, alarmPda),
            builder.buildSnooze(owner, alarmPda, sink, 0),
            builder.buildEmergencyRefund(owner, alarmPda, sink),
            builder.buildSlash(owner, alarmPda, sink)
        )

        for (ix in instructions) {
            assertEquals(SolarmaInstructionBuilder.PROGRAM_ID, ix.programId)
        }
    }

    // =========================================================================
    // Snooze expected_snooze_count encoding
    // =========================================================================

    @Test
    fun `snooze encodes different expected_snooze_count values`() {
        val alarmPda = builder.deriveAlarmPda(owner, 1L).address
        val sink = PublicKey("1nc1nerator11111111111111111111111111111111")

        for (count in listOf(0, 1, 5, 9, 255)) {
            val ix = builder.buildSnooze(owner, alarmPda, sink, count)
            assertEquals(9, ix.data.size)
            assertEquals(count.toByte(), ix.data[8])
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun sha256(input: String): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    }
}
