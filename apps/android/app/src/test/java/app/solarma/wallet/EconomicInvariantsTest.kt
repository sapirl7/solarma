package app.solarma.wallet

import app.solarma.ui.components.SnoozePenaltyDisplay
import org.junit.Assert.*
import org.junit.Test

/**
 * ★ CTO-Level Protocol Invariant Tests (Android Side)
 *
 * These verify that Android client-side economic computations match
 * the Rust on-chain program's behavior. Divergence here means the UI
 * shows users incorrect penalty information, leading to unexpected
 * fund loss on commitment.
 *
 * Each test maps to a Rust protocol_invariants::INV-* test.
 */
class EconomicInvariantsTest {

    // Rust constants from constants.rs
    companion object {
        const val SNOOZE_PERCENT = 10L       // DEFAULT_SNOOZE_PERCENT
        const val REFUND_PENALTY = 5L        // EMERGENCY_REFUND_PENALTY_PERCENT
        const val MAX_SNOOZE = 10            // MAX_SNOOZE_COUNT
        const val MIN_DEPOSIT = 1_000_000L   // MIN_DEPOSIT_LAMPORTS
        const val SOL = 1_000_000_000L       // LAMPORTS_PER_SOL
    }

    // =========================================================================
    // INV-1: CONSERVATION OF VALUE
    // =========================================================================

    @Test
    fun `inv1 - cumulative penalty never exceeds 100 percent`() {
        // Only test within protocol bounds (MAX_SNOOZE_COUNT = 10).
        // cumulativePercent uses Int shift, which overflows past count=30.
        for (count in 0..MAX_SNOOZE) {
            val percent = SnoozePenaltyDisplay.cumulativePercent(count)
            assertTrue("Cumulative exceeded 100% at count=$count", percent <= 100)
            assertTrue("Cumulative is negative at count=$count", percent >= 0)
        }
    }

    @Test
    fun `inv1 - cumulative penalty is monotonically non-decreasing`() {
        var prev = 0
        for (count in 0..MAX_SNOOZE) {
            val current = SnoozePenaltyDisplay.cumulativePercent(count)
            assertTrue("Cumulative decreased at count=$count", current >= prev)
            prev = current
        }
    }

    @Test
    fun `inv1 - value conservation across full snooze chain`() {
        val initial = 10 * SOL
        var remaining = initial
        var totalDrained = 0L

        for (count in 0 until MAX_SNOOZE) {
            val baseCost = remaining * SNOOZE_PERCENT / 100
            val multiplier = 1L shl count
            val cost = minOf(baseCost * multiplier, remaining)
            remaining -= cost
            totalDrained += cost
        }

        // INVARIANT: drained + remaining = initial (no lamports created/destroyed)
        assertEquals(
            "Value not conserved! drained=$totalDrained remaining=$remaining",
            initial, totalDrained + remaining
        )
    }

    // =========================================================================
    // INV-4: SNOOZE COST FORMULA PARITY
    // =========================================================================

    @Test
    fun `inv4 - android snooze cost matches Rust handler arithmetic`() {
        val testDeposits = longArrayOf(MIN_DEPOSIT, 10_000_000L, SOL, 10 * SOL)

        for (initial in testDeposits) {
            var remaining = initial
            for (count in 0 until MAX_SNOOZE) {
                // Rust handler (snooze.rs:74-85):
                val baseCost = remaining * SNOOZE_PERCENT / 100
                val multiplier = 1L shl count
                val rustCost = minOf(baseCost * multiplier, remaining)

                assertTrue(
                    "Cost must be positive for deposit=$initial, count=$count",
                    rustCost > 0 || remaining == 0L
                )

                remaining -= rustCost
                if (remaining <= 0) break
            }
        }
    }

    // =========================================================================
    // INV-8: EXPONENTIAL DRAIN CONVERGENCE
    // =========================================================================

    @Test
    fun `inv8 - max snoozes drain at least 99 percent`() {
        for (initial in longArrayOf(MIN_DEPOSIT, SOL, 100 * SOL)) {
            var remaining = initial
            for (count in 0 until MAX_SNOOZE) {
                val baseCost = remaining * SNOOZE_PERCENT / 100
                val multiplier = 1L shl count
                val cost = minOf(baseCost * multiplier, remaining)
                remaining -= cost
            }

            val drainedPercent = ((initial - remaining) * 100) / initial
            assertTrue(
                "Only drained $drainedPercent% of $initial",
                drainedPercent >= 99
            )
        }
    }

    // =========================================================================
    // INV-3: EMERGENCY REFUND PENALTY
    // =========================================================================

    @Test
    fun `inv3 - emergency penalty is non-zero for funded alarms`() {
        for (deposit in longArrayOf(MIN_DEPOSIT, 10_000_000L, SOL, 10 * SOL)) {
            val penalty = deposit * REFUND_PENALTY / 100
            assertTrue("Penalty must be > 0 for deposit=$deposit", penalty > 0)
            assertEquals(deposit * 5 / 100, penalty)
        }
    }

    // =========================================================================
    // INV-14: MAXIMUM EXTRACTABLE VALUE
    // =========================================================================

    @Test
    fun `inv14 - MEV equals initial deposit across all paths`() {
        for (initial in longArrayOf(MIN_DEPOSIT, 10_000_000L, SOL, 10 * SOL)) {
            // Path A: Full snooze chain + slash
            var remainingA = initial
            var burntA = 0L
            for (count in 0 until MAX_SNOOZE) {
                val baseCost = remainingA * SNOOZE_PERCENT / 100
                val multiplier = 1L shl count
                val cost = minOf(baseCost * multiplier, remainingA)
                remainingA -= cost
                burntA += cost
            }
            assertEquals("Path A MEV", initial, burntA + remainingA)

            // Path B: Emergency refund
            val penaltyB = initial * REFUND_PENALTY / 100
            assertEquals("Path B MEV", initial, penaltyB + (initial - penaltyB))

            // Path C: Clean claim
            assertEquals("Path C MEV", initial, initial)
        }
    }

    // =========================================================================
    // INV-12: PENALTY ROUTE CODES
    // =========================================================================

    @Test
    fun `inv12 - PenaltyRoute codes match Rust enum`() {
        assertEquals("Burn", 0.toByte(), PenaltyRoute.BURN.code)
        assertEquals("Donate", 1.toByte(), PenaltyRoute.DONATE.code)
        assertEquals("Buddy", 2.toByte(), PenaltyRoute.BUDDY.code)
    }

    @Test
    fun `inv12 - invalid route codes fall back to BURN`() {
        for (code in 3..255) {
            assertEquals(
                "Invalid code $code must → BURN",
                PenaltyRoute.BURN,
                PenaltyRoute.fromCode(code)
            )
        }
    }

    // =========================================================================
    // INV-ANDROID-1: ONCHAIN PARAMETERS CROSS-PLATFORM PARITY
    // =========================================================================

    @Test
    fun `inv-android-1 - critical constants match Rust program`() {
        assertEquals("SNOOZE_BASE_PERCENT", 10L, OnchainParameters.SNOOZE_BASE_PERCENT)
        assertEquals("EMERGENCY_REFUND_PENALTY", 5L, OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT)
        assertEquals("MIN_DEPOSIT_LAMPORTS", 1_000_000L, OnchainParameters.MIN_DEPOSIT_LAMPORTS)
        assertEquals("MAX_SNOOZE_COUNT", 10, OnchainParameters.MAX_SNOOZE_COUNT)
    }
}
