package app.solarma.wallet

import org.junit.Test
import org.junit.Assert.*
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for SolarmaInstructionBuilder.
 */
class SolarmaInstructionBuilderTest {

    private val builder = SolarmaInstructionBuilder()

    @Test
    fun `deriveAlarmPda returns consistent results`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val alarmId = 12345L

        val (pda1, bump1) = builder.deriveAlarmPda(owner, alarmId)
        val (pda2, bump2) = builder.deriveAlarmPda(owner, alarmId)

        assertEquals(pda1.toBase58(), pda2.toBase58())
        assertEquals(bump1, bump2)
    }

    @Test
    fun `deriveVaultPda is deterministic`() {
        val alarmPda = PublicKey("22222222222222222222222222222222")

        val (vault1, _) = builder.deriveVaultPda(alarmPda)
        val (vault2, _) = builder.deriveVaultPda(alarmPda)

        assertEquals(vault1.toBase58(), vault2.toBase58())
    }

    @Test
    fun `buildCreateAlarm produces valid instruction data`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val alarmId = 100L
        val alarmTime = 1700000000L
        val deadline = 1700010000L
        val deposit = 100_000_000L // 0.1 SOL

        val instruction = builder.buildCreateAlarm(
            owner = owner,
            alarmId = alarmId,
            alarmTime = alarmTime,
            deadline = deadline,
            depositLamports = deposit,
            penaltyRoute = 0,
            penaltyDestination = null
        )

        // Verify instruction data starts with discriminator (8 bytes)
        assertTrue(instruction.data.size >= 8)

        // Verify program ID
        assertEquals(SolarmaInstructionBuilder.PROGRAM_ID, instruction.programId)

        // Verify we have 4 accounts (alarm, vault, owner, system)
        assertEquals(4, instruction.accounts.size)
    }

    @Test
    fun `buildCreateAlarm with buddy includes destination`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val buddy = PublicKey("33333333333333333333333333333333")

        val instruction = builder.buildCreateAlarm(
            owner = owner,
            alarmId = 200L,
            alarmTime = 1700000000L,
            deadline = 1700010000L,
            depositLamports = 50_000_000L,
            penaltyRoute = 2, // BUDDY
            penaltyDestination = buddy
        )

        // Data should be longer when destination is included
        // 8 (discriminator) + 8 (alarmId) + 8 (time) + 8 (deadline) + 8 (deposit) + 1 (route) + 1 (Some) + 32 (pubkey)
        assertEquals(8 + 8 + 8 + 8 + 8 + 1 + 1 + 32, instruction.data.size)
    }

    @Test
    fun `buildClaim produces correct instruction`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val (alarmPda, _) = builder.deriveAlarmPda(owner, 300L)

        val instruction = builder.buildClaim(
            owner = owner,
            alarmPda = alarmPda
        )

        // Claim only has discriminator
        assertEquals(8, instruction.data.size)
        assertEquals(4, instruction.accounts.size)
    }

    @Test
    fun `buildSnooze includes sink address`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val (alarmPda, _) = builder.deriveAlarmPda(owner, 400L)
        val sink = PublicKey("44444444444444444444444444444444")

        val instruction = builder.buildSnooze(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = sink,
            expectedSnoozeCount = 0
        )

        // Snooze has 5 accounts (alarm, vault, sink, owner, system)
        assertEquals(5, instruction.accounts.size)
        assertEquals(sink, instruction.accounts[2].pubkey)
        // Data: 8-byte discriminator + 1-byte expected_snooze_count
        assertEquals(9, instruction.data.size)
    }

    @Test
    fun `buildEmergencyRefund produces correct instruction`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val (alarmPda, _) = builder.deriveAlarmPda(owner, 500L)
        val sink = PublicKey("1nc1nerator11111111111111111111111111111111")

        val instruction = builder.buildEmergencyRefund(
            owner = owner,
            alarmPda = alarmPda,
            sinkAddress = sink
        )

        assertEquals(8, instruction.data.size)
        assertEquals(5, instruction.accounts.size)
        assertEquals(sink, instruction.accounts[2].pubkey)
    }

    @Test
    fun `buildSlash produces correct instruction`() {
        val owner = PublicKey("11111111111111111111111111111111")
        val (alarmPda, _) = builder.deriveAlarmPda(owner, 600L)
        val recipient = PublicKey("1nc1nerator11111111111111111111111111111111")

        val instruction = builder.buildSlash(
            caller = owner,
            alarmPda = alarmPda,
            penaltyRecipient = recipient
        )

        assertEquals(8, instruction.data.size)
        assertEquals(5, instruction.accounts.size)
        assertEquals(recipient, instruction.accounts[2].pubkey)
    }
}
