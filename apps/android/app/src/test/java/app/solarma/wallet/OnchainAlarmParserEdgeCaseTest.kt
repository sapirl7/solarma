package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Extended edge-case tests for [OnchainAlarmParser].
 * Covers boundary conditions the happy-path tests don't reach.
 */
class OnchainAlarmParserEdgeCaseTest {

    // =========================================================================
    // Boundary: data too small
    // =========================================================================

    @Test
    fun `empty data returns null`() {
        val base64 = Base64.getEncoder().encodeToString(ByteArray(0))
        assertNull(OnchainAlarmParser.parse("11111111111111111111111111111111", base64))
    }

    @Test
    fun `data under 80 bytes returns null`() {
        val base64 = Base64.getEncoder().encodeToString(ByteArray(79))
        assertNull(OnchainAlarmParser.parse("11111111111111111111111111111111", base64))
    }

    @Test
    fun `invalid base64 returns null`() {
        // Completely invalid Base64 should be caught by the try-catch
        assertNull(OnchainAlarmParser.parse("11111111111111111111111111111111", "!!!not-base64!!!"))
    }

    // =========================================================================
    // All 4 alarm statuses parse correctly
    // =========================================================================

    @Test
    fun `status Created (0) parses correctly`() {
        val parsed = buildAndParse(status = 0)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.status)
    }

    @Test
    fun `status Acknowledged (1) parses correctly`() {
        val parsed = buildAndParse(status = 1)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.status)
    }

    @Test
    fun `status Claimed (2) parses correctly`() {
        val parsed = buildAndParse(status = 2)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.status)
    }

    @Test
    fun `status Slashed (3) parses correctly`() {
        val parsed = buildAndParse(status = 3)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.status)
    }

    // =========================================================================
    // All 3 penalty routes
    // =========================================================================

    @Test
    fun `penalty route BURN (0) with no destination`() {
        val parsed = buildAndParse(penaltyRoute = 0, penaltyDestination = null)
        assertNotNull(parsed)
        assertEquals(0, parsed!!.penaltyRoute)
        assertNull(parsed.penaltyDestination)
    }

    @Test
    fun `penalty route DONATE (1) with destination`() {
        val dest = "1nc1nerator11111111111111111111111111111111"
        val parsed = buildAndParse(penaltyRoute = 1, penaltyDestination = dest)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.penaltyRoute)
        assertEquals(dest, parsed.penaltyDestination)
    }

    @Test
    fun `penalty route BUDDY (2) with destination`() {
        val dest = "11111111111111111111111111111111"
        val parsed = buildAndParse(penaltyRoute = 2, penaltyDestination = dest)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.penaltyRoute)
        assertEquals(dest, parsed.penaltyDestination)
    }

    // =========================================================================
    // Extreme values
    // =========================================================================

    @Test
    fun `max snooze count 255 parses as unsigned byte`() {
        val parsed = buildAndParse(snoozeCount = 255)
        assertNotNull(parsed)
        assertEquals(255, parsed!!.snoozeCount) // 0xFF as unsigned
    }

    @Test
    fun `max u64 amounts parse correctly`() {
        val parsed = buildAndParse(
            initialAmount = Long.MAX_VALUE,
            remainingAmount = Long.MAX_VALUE
        )
        assertNotNull(parsed)
        assertEquals(Long.MAX_VALUE, parsed!!.initialAmount)
        assertEquals(Long.MAX_VALUE, parsed!!.remainingAmount)
    }

    @Test
    fun `zero deposit alarm parses correctly`() {
        val parsed = buildAndParse(initialAmount = 0, remainingAmount = 0)
        assertNotNull(parsed)
        assertEquals(0L, parsed!!.initialAmount)
        assertEquals(0L, parsed!!.remainingAmount)
    }

    @Test
    fun `negative alarm time (before epoch) parses correctly`() {
        val parsed = buildAndParse(alarmTime = -1000L)
        assertNotNull(parsed)
        assertEquals(-1000L, parsed!!.alarmTimeUnix)
    }

    // =========================================================================
    // OnchainAlarmAccount data class
    // =========================================================================

    @Test
    fun `OnchainAlarmAccount equals and hashCode work correctly`() {
        val a = OnchainAlarmAccount(
            pubkey = "aaa",
            owner = "bbb",
            alarmTimeUnix = 100L,
            deadlineUnix = 200L,
            initialAmount = 1000L,
            remainingAmount = 500L,
            penaltyRoute = 0,
            penaltyDestination = null,
            snoozeCount = 0,
            status = 0
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `OnchainAlarmAccount copy with changes`() {
        val original = OnchainAlarmAccount(
            pubkey = "aaa",
            owner = "bbb",
            alarmTimeUnix = 100L,
            deadlineUnix = 200L,
            initialAmount = 1000L,
            remainingAmount = 500L,
            penaltyRoute = 0,
            penaltyDestination = null,
            snoozeCount = 0,
            status = 0
        )
        val snoozed = original.copy(snoozeCount = 1, remainingAmount = 400L)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(400L, snoozed.remainingAmount)
        assertNotEquals(original, snoozed)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun buildAndParse(
        owner: String = "11111111111111111111111111111111",
        alarmTime: Long = 1_700_000_000L,
        deadline: Long = 1_700_001_800L,
        initialAmount: Long = 1_000_000_000L,
        remainingAmount: Long = 1_000_000_000L,
        penaltyRoute: Int = 0,
        penaltyDestination: String? = null,
        snoozeCount: Int = 0,
        status: Int = 0,
        alarmId: Long = 1L
    ): OnchainAlarmAccount? {
        val disc = discriminator("account:Alarm")
        val ownerBytes = PublicKey(owner).bytes()
        val destBytes = penaltyDestination?.let { PublicKey(it).bytes() }

        val size = 8 + 32 + 8 + 8 + 8 + 8 + 8 + 1 +
            1 + (destBytes?.size ?: 0) +
            1 + 1 + 1 + 1 + 32

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(disc)
        buffer.put(ownerBytes)
        buffer.putLong(alarmId)
        buffer.putLong(alarmTime)
        buffer.putLong(deadline)
        buffer.putLong(initialAmount)
        buffer.putLong(remainingAmount)
        buffer.put(penaltyRoute.toByte())
        if (destBytes != null) {
            buffer.put(1.toByte())
            buffer.put(destBytes)
        } else {
            buffer.put(0.toByte())
        }
        buffer.put(snoozeCount.toByte())
        buffer.put(status.toByte())
        buffer.put(1.toByte()) // bump
        buffer.put(2.toByte()) // vault_bump
        buffer.put(ByteArray(32))

        val base64 = Base64.getEncoder().encodeToString(buffer.array())
        return OnchainAlarmParser.parse("test_pubkey", base64)
    }

    private fun discriminator(name: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(name.toByteArray()).copyOfRange(0, 8)
    }
}
