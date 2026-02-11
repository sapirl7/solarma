package app.solarma.wallet

/**
 * Onchain economic parameters mirrored in app logic.
 */
object OnchainParameters {
    const val SNOOZE_BASE_PERCENT = 10L
    const val EMERGENCY_REFUND_PENALTY_PERCENT = 5L
    const val MIN_DEPOSIT_LAMPORTS = 1_000_000L
    const val MAX_SNOOZE_COUNT = 10
}
