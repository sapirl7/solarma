package app.solarma.alarm

import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Computes the next trigger time for a given LocalTime.
 */
object AlarmTimeCalculator {
    fun nextTriggerMillis(time: LocalTime, clock: Clock = Clock.systemDefaultZone()): Long {
        val now = LocalDateTime.now(clock)
        var targetDateTime = LocalDateTime.of(now.toLocalDate(), time)

        if (targetDateTime.isBefore(now)) {
            targetDateTime = targetDateTime.plusDays(1)
        }

        return targetDateTime
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()
    }
}
