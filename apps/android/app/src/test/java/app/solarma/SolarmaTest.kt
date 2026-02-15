package app.solarma

import app.solarma.alarm.AlarmTimeCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Unit tests for Solarma.
 */
class SolarmaTest {
    @Test
    fun `next trigger uses same day when time is in the future`() {
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(Instant.parse("2026-02-03T10:00:00Z"), zone)

        val trigger = AlarmTimeCalculator.nextTriggerMillis(LocalTime.of(11, 0), clock)

        assertEquals(Instant.parse("2026-02-03T11:00:00Z").toEpochMilli(), trigger)
    }

    @Test
    fun `next trigger rolls to next day when time already passed`() {
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(Instant.parse("2026-02-03T10:00:00Z"), zone)

        val trigger = AlarmTimeCalculator.nextTriggerMillis(LocalTime.of(9, 0), clock)

        assertEquals(Instant.parse("2026-02-04T09:00:00Z").toEpochMilli(), trigger)
    }
}
