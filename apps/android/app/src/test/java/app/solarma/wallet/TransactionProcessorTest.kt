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
        val safeCount = snoozeCount.coerceIn(0, 30)
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
    fun `negative snooze count produces non-negative cost`() {
        val cost = computeSnoozeCost(1_000_000L, -1)
        assertTrue("Negative snooze count must not produce negative cost", cost >= 0)
        assertTrue("Cost must not exceed remaining", cost <= 1_000_000L)
    }

    @Test
    fun `snooze count 20 with 10 SOL overflows safely`() {
        // 10_000_000_000 * 10% = 1_000_000_000 baseCost
        // multiplier = 2^20 = 1_048_576
        // 1_000_000_000 * 1_048_576 > Long.MAX_VALUE → overflow guard caps at remaining
        val remaining = 10_000_000_000L
        val cost = computeSnoozeCost(remaining, 20)
        assertTrue("Cost must be non-negative", cost >= 0)
        assertTrue("Cost must not exceed remaining", cost <= remaining)
    }

    @Test
    fun `snooze cost never exceeds remaining for any count 0-30`() {
        val remaining = 5_000_000_000L
        for (count in 0..30) {
            val cost = computeSnoozeCost(remaining, count)
            assertTrue("count=$count: cost=$cost must be >= 0", cost >= 0)
            assertTrue("count=$count: cost=$cost must be <= remaining", cost <= remaining)
        }
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

    @Test
    fun `emergency refund penalty on zero deposit`() {
        val deposit = 0L
        val penalty = deposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(0L, penalty)
    }

    @Test
    fun `emergency refund penalty splits correctly`() {
        val deposit = 2_000_000_000L  // 2 SOL
        val penalty = deposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        val refund = deposit - penalty
        assertEquals(100_000_000L, penalty)
        assertEquals(1_900_000_000L, refund)
        assertEquals(deposit, penalty + refund)
    }

    @Test
    fun `emergency refund penalty rounds down for small amounts`() {
        // 19 lamports * 5 / 100 = 0 (integer division)
        val penalty = 19L * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(0L, penalty)
    }

    // ── isDeadlinePassed (deadline guard for CLAIM / ACK_AWAKE) ──

    @Test
    fun `deadline not passed when now is before alarm time`() {
        val alarmTimeMillis = 1_000_000_000L
        val nowMillis = 500_000_000L // Way before alarm time
        assertFalse(isDeadlinePassed(alarmTimeMillis, nowMillis))
    }

    @Test
    fun `deadline not passed when now is between alarm time and deadline`() {
        val alarmTimeMillis = 1_000_000_000L
        val nowMillis = alarmTimeMillis + 1000L // 1 second after alarm, still within grace period
        assertFalse(isDeadlinePassed(alarmTimeMillis, nowMillis))
    }

    @Test
    fun `deadline passed exactly at deadline boundary`() {
        val alarmTimeMillis = 1_000_000_000L
        val deadlineMillis = alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
        assertTrue(isDeadlinePassed(alarmTimeMillis, deadlineMillis))
    }

    @Test
    fun `deadline passed well after grace period`() {
        val alarmTimeMillis = 1_000_000_000L
        val nowMillis = alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS + 60_000L
        assertTrue(isDeadlinePassed(alarmTimeMillis, nowMillis))
    }

    @Test
    fun `deadline not passed one ms before boundary`() {
        val alarmTimeMillis = 1_000_000_000L
        val deadlineMillis = alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
        assertFalse(isDeadlinePassed(alarmTimeMillis, deadlineMillis - 1))
    }

    @Test
    fun `grace period is 30 minutes`() {
        assertEquals(1_800_000L, app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS)
    }

    // ── ErrorClassifier at status.err path ──

    @Test
    fun `status err InvalidAlarmState is NON_RETRYABLE`() {
        // Simulates the status.err path in TransactionProcessor line 238
        val statusErr = "InvalidAlarmState"
        assertEquals(ErrorClassifier.Category.NON_RETRYABLE, ErrorClassifier.classify(statusErr))
    }

    @Test
    fun `status err blockhash not found is RETRYABLE`() {
        val statusErr = "blockhash not found"
        assertEquals(ErrorClassifier.Category.RETRYABLE, ErrorClassifier.classify(statusErr))
    }

    @Test
    fun `status err DeadlinePassed is NON_RETRYABLE`() {
        val statusErr = "DeadlinePassed"
        assertEquals(ErrorClassifier.Category.NON_RETRYABLE, ErrorClassifier.classify(statusErr))
    }

    companion object {
        // Mirror isDeadlinePassed logic for testing without constructing TransactionProcessor
        private fun isDeadlinePassed(alarmTimeMillis: Long, nowMillis: Long): Boolean {
            val deadlineMillis = alarmTimeMillis + app.solarma.alarm.AlarmTiming.GRACE_PERIOD_MILLIS
            return nowMillis >= deadlineMillis
        }
    }
}
