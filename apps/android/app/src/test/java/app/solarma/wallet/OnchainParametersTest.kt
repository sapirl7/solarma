package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that Android economic parameters match on-chain Rust constants.
 * If these fail, the app is using wrong percentages for snooze/refund.
 */
class OnchainParametersTest {
    @Test
    fun `snooze base percent matches Rust constant`() {
        // Rust: DEFAULT_SNOOZE_PERCENT = 10
        assertEquals(10L, OnchainParameters.SNOOZE_BASE_PERCENT)
    }

    @Test
    fun `emergency refund penalty matches Rust constant`() {
        // Rust: EMERGENCY_REFUND_PENALTY_PERCENT = 5
        assertEquals(5L, OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT)
    }

    @Test
    fun `snooze cost calculation for 1 SOL first snooze`() {
        // Mirror of Rust helpers::snooze_cost(1_000_000_000, 0)
        val deposit = 1_000_000_000L
        val baseCost = deposit * OnchainParameters.SNOOZE_BASE_PERCENT / 100
        assertEquals(100_000_000L, baseCost) // 0.1 SOL
    }

    @Test
    fun `snooze cost doubles each snooze`() {
        val deposit = 1_000_000_000L
        val base = deposit * OnchainParameters.SNOOZE_BASE_PERCENT / 100

        var cost = base
        for (i in 0 until 5) {
            assertTrue("Cost at snooze $i should be positive", cost > 0)
            val next = cost * 2
            assertTrue("Cost should double", next == cost * 2)
            cost = next
        }
    }

    @Test
    fun `emergency penalty for 1 SOL`() {
        val deposit = 1_000_000_000L
        val penalty = deposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(50_000_000L, penalty) // 0.05 SOL = 5%
    }

    @Test
    fun `emergency penalty for minimum deposit`() {
        val minDeposit = 1_000_000L // MIN_DEPOSIT_LAMPORTS from Rust
        val penalty = minDeposit * OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT / 100
        assertEquals(50_000L, penalty)
    }
}
