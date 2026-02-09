package app.solarma.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [SnoozePenaltyDisplay] — verifies the exponential formula matches on-chain.
 *
 * On-chain formula (snooze.rs):
 *   cost = (remaining × DEFAULT_SNOOZE_PERCENT / 100) × 2^snooze_count
 *   cumulative = 10% × (2^n - 1)
 */
class SnoozePenaltyDisplayTest {

    @Test
    fun `zero snoozes means zero penalty`() {
        assertEquals(0, SnoozePenaltyDisplay.cumulativePercent(0))
    }
    
    @Test
    fun `first snooze costs 10 percent`() {
        // 10% * (2^1 - 1) = 10%
        assertEquals(10, SnoozePenaltyDisplay.cumulativePercent(1))
    }
    
    @Test
    fun `second snooze cumulative is 30 percent`() {
        // 10% * (2^2 - 1) = 30%
        assertEquals(30, SnoozePenaltyDisplay.cumulativePercent(2))
    }
    
    @Test
    fun `third snooze cumulative is 70 percent`() {
        // 10% * (2^3 - 1) = 70%
        assertEquals(70, SnoozePenaltyDisplay.cumulativePercent(3))
    }
    
    @Test
    fun `high snooze count caps at 100 percent`() {
        // 10% * (2^10 - 1) = 10230% → capped at 100%
        assertEquals(100, SnoozePenaltyDisplay.cumulativePercent(10))
    }
    
    @Test
    fun `negative snooze count returns zero`() {
        assertEquals(0, SnoozePenaltyDisplay.cumulativePercent(-1))
    }
    
    @Test
    fun `format display includes count and percentage`() {
        assertEquals("2× (30% penalized)", SnoozePenaltyDisplay.formatDisplay(2))
    }
    
    @Test
    fun `format display for first snooze`() {
        assertEquals("1× (10% penalized)", SnoozePenaltyDisplay.formatDisplay(1))
    }
}
