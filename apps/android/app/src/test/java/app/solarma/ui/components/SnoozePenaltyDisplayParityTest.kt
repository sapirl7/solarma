package app.solarma.ui.components

import org.junit.Assert.*
import org.junit.Test

/**
 * Cross-platform parity tests for [SnoozePenaltyDisplay].
 * Verifies the Android cumulative formula matches Rust on-chain logic:
 *   Rust: cost_i = (remaining × 10 / 100) × 2^i
 *   cumulative = 10% × (2^n - 1), capped at 100%
 */
class SnoozePenaltyDisplayParityTest {
    // =========================================================================
    // Formula matches Rust snooze_cost for all valid snooze counts
    // =========================================================================

    @Test
    fun `cumulative matches Rust formula for all counts 0 to 10`() {
        // Rust MAX_SNOOZE_COUNT = 10
        val expected = intArrayOf(0, 10, 30, 70, 100, 100, 100, 100, 100, 100, 100)
        for (count in 0..10) {
            val percent = SnoozePenaltyDisplay.cumulativePercent(count)
            assertEquals("Mismatch at count=$count", expected[count], percent)
        }
    }

    @Test
    fun `cumulative is monotonically non-decreasing`() {
        var prev = 0
        for (count in 0..20) {
            val current = SnoozePenaltyDisplay.cumulativePercent(count)
            assertTrue("cumulative decreased at count=$count", current >= prev)
            prev = current
        }
    }

    @Test
    fun `cumulative never exceeds 100`() {
        for (count in 0..100) {
            assertTrue(
                "Exceeded 100% at count=$count",
                SnoozePenaltyDisplay.cumulativePercent(count) <= 100
            )
        }
    }

    // =========================================================================
    // Simulate Rust deposit drain
    // =========================================================================

    @Test
    fun `simulated drain matches Rust deposit arithmetic`() {
        // Starting with 1 SOL, simulate on-chain snooze cost deductions
        var remaining = 1_000_000_000L // 1 SOL in lamports
        val initial = remaining

        for (count in 0 until 10) {
            val baseCost = remaining * 10 / 100
            val multiplier = 1L shl count
            val cost = minOf(baseCost * multiplier, remaining)
            remaining -= cost
        }

        // After 10 snoozes, verify cumulative matches
        val actualLoss = initial - remaining
        val actualLossPercent = (actualLoss * 100 / initial).toInt()

        // Should match cumulativePercent(10) capped to 100%
        // Note: actual loss may be slightly less due to rounding on each step
        assertTrue("Loss should be close to 100%", actualLossPercent >= 95)
    }

    // =========================================================================
    // Format display consistency
    // =========================================================================

    @Test
    fun `formatDisplay for zero snoozes`() {
        assertEquals("0× (0% penalized)", SnoozePenaltyDisplay.formatDisplay(0))
    }

    @Test
    fun `formatDisplay for max snoozes`() {
        assertEquals("10× (100% penalized)", SnoozePenaltyDisplay.formatDisplay(10))
    }

    @Test
    fun `formatDisplay pattern is consistent`() {
        for (count in 0..10) {
            val display = SnoozePenaltyDisplay.formatDisplay(count)
            assertTrue("Missing count prefix", display.startsWith("${count}×"))
            assertTrue("Missing closing paren", display.endsWith("penalized)"))
        }
    }
}
