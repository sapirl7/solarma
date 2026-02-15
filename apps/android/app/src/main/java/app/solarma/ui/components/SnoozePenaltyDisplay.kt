package app.solarma.ui.components

import app.solarma.alarm.AlarmTiming

/**
 * Display utilities for snooze penalty calculations.
 *
 * Matches the on-chain formula in `programs/solarma_vault/src/instructions/snooze.rs`:
 *   cost = (remaining × DEFAULT_SNOOZE_PERCENT / 100) × 2^snooze_count
 *
 * The penalty is EXPONENTIAL — each snooze doubles the cost.
 */
object SnoozePenaltyDisplay {
    /** On-chain default: 10% base penalty per snooze */
    private const val SNOOZE_BASE_PERCENT = 10

    /**
     * Calculate the cumulative penalty percentage paid after [snoozeCount] snoozes.
     *
     * The on-chain formula applies 10% × 2^n per snooze, where n is the snooze index
     * (0-based at time of each snooze). The total cumulative loss is:
     *   sum(10% × 2^i for i in 0..snoozeCount-1) = 10% × (2^snoozeCount - 1)
     *
     * This is capped at 100%.
     */
    fun cumulativePercent(snoozeCount: Int): Int {
        if (snoozeCount <= 0) return 0
        val total = SNOOZE_BASE_PERCENT * ((1 shl snoozeCount) - 1)
        return total.coerceAtMost(100)
    }

    /**
     * Format snooze count with cumulative penalty for display.
     * Example: "2× (30% penalized)" or "1× (10% penalized)"
     */
    fun formatDisplay(snoozeCount: Int): String {
        val percent = cumulativePercent(snoozeCount)
        return "${snoozeCount}× (${percent}% penalized)"
    }
}
