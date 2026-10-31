package app.solarma.alarm

import app.solarma.data.local.AlarmEntity

/**
 * Shared timing constants for alarms and onchain deadlines.
 */
object AlarmTiming {
    const val GRACE_PERIOD_SECONDS = 1800L
    const val GRACE_PERIOD_MILLIS = GRACE_PERIOD_SECONDS * 1000L
    const val SNOOZE_MINUTES = 5
    const val SNOOZE_MILLIS = SNOOZE_MINUTES * 60 * 1000L

    /** Time that must remain before the deadline when a snoozed alarm rings again,
     *  so the user can finish the wake proof and confirm on-chain. */
    const val DEADLINE_SAFETY_MILLIS = 5 * 60 * 1000L

    /** Shortest snooze worth offering. */
    private const val MIN_SNOOZE_MILLIS = 60 * 1000L

    /**
     * On-chain deadline in millis. Uses the stored on-chain value; alarms created
     * before it was tracked fall back to alarm time + grace.
     */
    fun deadlineMillis(alarm: AlarmEntity): Long =
        alarm.onchainDeadline?.let { it * 1000 } ?: (alarm.alarmTimeMillis + GRACE_PERIOD_MILLIS)

    /**
     * Next ring time for a snooze pressed at [nowMillis], or null if the alarm must not
     * be snoozed. Deposit alarms never ring later than [DEADLINE_SAFETY_MILLIS] before the
     * on-chain deadline: the snooze transaction only extends the deadline once it lands,
     * which usually happens later, when the app is opened.
     */
    fun snoozeRingMillis(
        alarm: AlarmEntity,
        nowMillis: Long,
    ): Long? {
        val next = nowMillis + SNOOZE_MILLIS
        if (!alarm.hasDeposit) return next
        val latest = deadlineMillis(alarm) - DEADLINE_SAFETY_MILLIS
        return if (latest >= nowMillis + MIN_SNOOZE_MILLIS) minOf(next, latest) else null
    }
}
