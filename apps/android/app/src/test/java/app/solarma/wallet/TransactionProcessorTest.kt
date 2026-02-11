package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for TransactionProcessor.computeSnoozeCost — pure math that mirrors
 * the Rust on-chain logic: baseCost = remaining * 10% then doubled per snooze.
 * 
 * OnchainParameters constants are validated separately in OnchainParametersTest.
 */
class TransactionProcessorTest {

    // Mirror production logic exactly for parity testing in a standalone function.
    // The actual computeSnoozeCost is internal on TransactionProcessor but requires
    // Android Context dependencies to construct. We test via logic parity.
    private fun computeSnoozeCost(remaining: Long, snoozeCount: Int): Long {
        if (remaining <= 0) return 0
        val baseCost = remaining * OnchainParameters.SNOOZE_BASE_PERCENT / 100
        if (baseCost <= 0) return 0
        val safeCount = snoozeCount.coerceAtMost(30)
        val multiplier = 1L shl safeCount
        val cost = if (multiplier > 0 && baseCost > Long.MAX_VALUE / multiplier) {
            remaining
        } else {
            baseCost * multiplier
        }
        return kotlin.math.min(cost, remaining)
    }

    // ── Basic cases ──

    @Test
    fun `zero remaining returns zero cost`() {
        assertEquals(0, computeSnoozeCost(0, 0))
    }

    @Test
    fun `negative remaining returns zero cost`() {
        assertEquals(0, computeSnoozeCost(-100, 0))
    }

    @Test
    fun `first snooze costs 10 percent`() {
        assertEquals(100_000L, computeSnoozeCost(1_000_000L, 0))
    }

    @Test
    fun `second snooze doubles cost`() {
        assertEquals(200_000L, computeSnoozeCost(1_000_000L, 1))
    }

    @Test
    fun `third snooze quadruples cost`() {
        assertEquals(400_000L, computeSnoozeCost(1_000_000L, 2))
    }

    @Test
    fun `fourth snooze 800K`() {
        assertEquals(800_000L, computeSnoozeCost(1_000_000L, 3))
    }

    @Test
    fun `cost capped at remaining when exceeds`() {
        assertEquals(1_000_000L, computeSnoozeCost(1_000_000L, 4))
    }

    // ── Edge cases ──

    @Test
    fun `min deposit first snooze`() {
        val minDeposit = OnchainParameters.MIN_DEPOSIT_LAMPORTS
        assertEquals(minDeposit / 10, computeSnoozeCost(minDeposit, 0))
    }

    @Test
    fun `5 SOL first snooze`() {
        assertEquals(500_000_000L, computeSnoozeCost(5_000_000_000L, 0))
    }

    @Test
    fun `snooze count 5 caps at remaining`() {
        assertEquals(10_000_000L, computeSnoozeCost(10_000_000L, 5))
    }

    @Test
    fun `snooze count 10 caps at remaining`() {
        assertEquals(1_000_000L, computeSnoozeCost(1_000_000L, 10))
    }

    @Test
    fun `remaining 99 gives baseCost 9`() {
        assertEquals(9L, computeSnoozeCost(99L, 0))
    }

    @Test
    fun `remaining 9 gives zero baseCost`() {
        assertEquals(0L, computeSnoozeCost(9L, 0))
    }

    @Test
    fun `remaining 10 gives baseCost 1`() {
        assertEquals(1L, computeSnoozeCost(10L, 0))
    }

    @Test
    fun `snooze count 30 overflow caps at remaining`() {
        val remaining = 1_000_000_000L
        assertEquals(remaining, computeSnoozeCost(remaining, 30))
    }

    @Test
    fun `snooze count above 30 is clamped to 30`() {
        assertEquals(1_000_000L, computeSnoozeCost(1_000_000L, 50))
    }

    @Test
    fun `negative snooze count does not crash`() {
        val cost = computeSnoozeCost(1_000_000L, -1)
        assertTrue(cost >= 0)
    }

    // ── Snooze cost progression ──

    @Test
    fun `snooze cost progression for 1 SOL`() {
        val deposit = 1_000_000_000L
        assertEquals(100_000_000L, computeSnoozeCost(deposit, 0))
        val r1 = deposit - computeSnoozeCost(deposit, 0)
        assertEquals(900_000_000L, r1)
        assertEquals(180_000_000L, computeSnoozeCost(r1, 1))
    }

    @Test
    fun `full snooze sequence depletes deposit`() {
        var remaining = 1_000_000L
        var snoozeCount = 0
        while (remaining > 0 && snoozeCount <= OnchainParameters.MAX_SNOOZE_COUNT) {
            val cost = computeSnoozeCost(remaining, snoozeCount)
            if (cost == 0L) break
            remaining -= cost
            snoozeCount++
            assertTrue("remaining should never go negative", remaining >= 0)
        }
        assertTrue("remaining should decrease: $remaining", remaining < 1_000_000L)
    }

    // ── Emergency refund calculations ──

    @Test
    fun `emergency refund penalty 5 percent of 1 SOL`() {
        val deposit = 1_000_000_000L
        val penalty = deposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(50_000_000L, penalty)
        assertEquals(950_000_000L, deposit - penalty)
    }

    @Test
    fun `emergency refund penalty on min deposit`() {
        val deposit = OnchainParameters.MIN_DEPOSIT_LAMPORTS
        val penalty = deposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(50_000L, penalty)
    }
}
