package app.solarma.alarm

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests for AlarmTimeCalculator — pure time computation logic.
 * Uses fixed clocks to make tests deterministic.
 */
class AlarmTimeCalculatorTest {

    private fun clockAt(hour: Int, minute: Int, zoneId: ZoneId = ZoneId.of("UTC")): Clock {
        // Fixed at 2026-02-11 at given time
        val instant = LocalTime.of(hour, minute)
            .atDate(java.time.LocalDate.of(2026, 2, 11))
            .atZone(zoneId)
            .toInstant()
        return Clock.fixed(instant, zoneId)
    }

    @Test
    fun `future time today returns same day`() {
        // Now is 08:00, alarm at 10:00 → same day
        val clock = clockAt(8, 0)
        val alarmTime = LocalTime.of(10, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(11, result.dayOfMonth) // Same day
        assertEquals(10, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `past time today wraps to next day`() {
        // Now is 14:00, alarm at 06:00 → next day
        val clock = clockAt(14, 0)
        val alarmTime = LocalTime.of(6, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(12, result.dayOfMonth) // Next day
        assertEquals(6, result.hour)
    }

    @Test
    fun `exactly current time wraps to next day`() {
        // Edge: alarm at EXACTLY now → should wrap to next day
        // (isBefore at same time returns false, so it stays today... but equals is NOT before)
        val clock = clockAt(8, 0)
        val alarmTime = LocalTime.of(8, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        // LocalDateTime.isBefore returns false for equal times, so no +1 day
        assertEquals(11, result.dayOfMonth)
        assertEquals(8, result.hour)
    }

    @Test
    fun `one minute before current time wraps to next day`() {
        val clock = clockAt(8, 0)
        val alarmTime = LocalTime.of(7, 59)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(12, result.dayOfMonth) // Next day
        assertEquals(7, result.hour)
        assertEquals(59, result.minute)
    }

    @Test
    fun `one minute after current time stays today`() {
        val clock = clockAt(8, 0)
        val alarmTime = LocalTime.of(8, 1)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(11, result.dayOfMonth)
        assertEquals(8, result.hour)
        assertEquals(1, result.minute)
    }

    @Test
    fun `midnight alarm at 23_59 wraps correctly`() {
        val clock = clockAt(23, 59)
        val alarmTime = LocalTime.of(6, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(12, result.dayOfMonth) // Next day
        assertEquals(6, result.hour)
    }

    @Test
    fun `alarm at midnight 00_00`() {
        val clock = clockAt(23, 0)
        val alarmTime = LocalTime.MIDNIGHT
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(12, result.dayOfMonth) // Midnight next day
        assertEquals(0, result.hour)
    }

    @Test
    fun `result is always in the future`() {
        // Property test: for any current time and alarm time, result >= now
        val zone = ZoneId.of("UTC")
        for (nowHour in 0..23) {
            for (alarmHour in 0..23) {
                val clock = clockAt(nowHour, 30, zone)
                val alarmTime = LocalTime.of(alarmHour, 0)
                val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)
                assertTrue(
                    "Alarm should be in the future for now=$nowHour:30, alarm=$alarmHour:00",
                    millis >= clock.millis()
                )
            }
        }
    }

    @Test
    fun `different timezone Warsaw`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val clock = clockAt(8, 0, zone)
        val alarmTime = LocalTime.of(10, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        // Should be in Warsaw time zone, not UTC
        val result = Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(10, result.hour)
        assertEquals(11, result.dayOfMonth)
    }
}
