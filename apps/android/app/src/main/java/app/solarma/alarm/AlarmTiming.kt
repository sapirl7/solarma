package app.solarma.alarm

/**
 * Shared timing constants for alarms and onchain deadlines.
 */
object AlarmTiming {
    const val GRACE_PERIOD_SECONDS = 1800L
    const val GRACE_PERIOD_MILLIS = GRACE_PERIOD_SECONDS * 1000L
    const val SNOOZE_MINUTES = 5
    const val SNOOZE_MILLIS = SNOOZE_MINUTES * 60 * 1000L
}
