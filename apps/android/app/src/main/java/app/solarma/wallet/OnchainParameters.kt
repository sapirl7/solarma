package app.solarma.wallet

/**
 * Onchain economic parameters mirrored in app logic.
 */
object OnchainParameters {
    const val SNOOZE_BASE_PERCENT = 10L
    const val EMERGENCY_REFUND_PENALTY_PERCENT = 5L
    const val MIN_DEPOSIT_LAMPORTS = 1_000_000L
    const val MAX_SNOOZE_COUNT = 10

    /** Seconds after the deadline during which an acknowledged alarm can still be claimed (CLAIM_GRACE_SECONDS). */
    const val CLAIM_GRACE_SECONDS = 120L

    /** Seconds each snooze adds to alarm_time and deadline (DEFAULT_SNOOZE_EXTENSION_SECONDS). */
    const val SNOOZE_EXTENSION_SECONDS = 300L

    /** AlarmStatus discriminants (state.rs). Resolved alarms are closed, so 2/3 only appear on legacy accounts. */
    const val STATUS_CREATED = 0
    const val STATUS_ACKNOWLEDGED = 1
}
