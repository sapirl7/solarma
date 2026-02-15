package app.solarma.ui.components

/**
 * Display utilities for penalty routes.
 *
 * Maps on-chain penalty route integers to user-facing labels and emoji.
 * Single source of truth — avoids duplicating `when` blocks across screens.
 */
object PenaltyRouteDisplay {
    data class PenaltyInfo(
        val emoji: String,
        val label: String
    ) {
        /** Formatted string like "🔥 Burn" */
        val formatted: String get() = "$emoji $label"
    }

    private val BURN = PenaltyInfo("🔥", "Burn")
    private val DONATE = PenaltyInfo("🎁", "Donate")
    private val BUDDY = PenaltyInfo("👋", "Buddy")
    private val UNKNOWN = PenaltyInfo("", "Unknown")

    /**
     * Map on-chain penalty route integer to display info.
     *
     * @param route 0=Burn, 1=Donate, 2=Buddy (matches PenaltyRoute Anchor enum)
     */
    fun fromRoute(route: Int): PenaltyInfo = when (route) {
        0 -> BURN
        1 -> DONATE
        2 -> BUDDY
        else -> UNKNOWN
    }
}
