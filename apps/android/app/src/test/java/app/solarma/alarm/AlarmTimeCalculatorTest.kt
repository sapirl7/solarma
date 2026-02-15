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
    private fun clockAt(
        hour: Int,
        minute: Int,
        zoneId: ZoneId = ZoneId.of("UTC"),
    ): Clock {
        // Fixed at 2026-02-11 at given time
        val instant =
            LocalTime.of(hour, minute)
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
    fun `exactly current time stays same day - isBefore contract`() {
        // Contract: LocalDateTime.isBefore(same) == false, so no plusDays(1).
        // This means alarm scheduled for NOW fires today, not tomorrow.
        val clock = clockAt(8, 0)
        val alarmTime = LocalTime.of(8, 0)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals("Same day because isBefore returns false for equal", 11, result.dayOfMonth)
        assertEquals(8, result.hour)
        assertEquals(0, result.minute)
        assertEquals(0, result.second)
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
                    millis >= clock.millis(),
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

    // ── DST edge cases ──

    @Test
    fun `DST spring forward - alarm in gap is pushed to 3_00`() {
        // Europe/Warsaw: 2026-03-29, clocks skip 02:00 → 03:00 (CET→CEST)
        val zone = ZoneId.of("Europe/Warsaw")
        val instant =
            java.time.LocalDate.of(2026, 3, 29)
                .atTime(1, 0)
                .atZone(zone)
                .toInstant()
        val clock = Clock.fixed(instant, zone)

        // 2:30 AM doesn't exist — Java LocalDateTime.atZone pushes it to 3:30
        val alarmTime = LocalTime.of(2, 30)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        assertTrue("Result should be in the future", millis > clock.millis())
        val result = Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(29, result.dayOfMonth) // Same day
        // Gap: 2:30 → pushed to 3:30 (CEST = UTC+2)
        assertEquals(3, result.hour)
        assertEquals(30, result.minute)
        assertEquals("+02:00", result.offset.toString()) // CEST
    }

    @Test
    fun `DST fall back - alarm in repeated hour uses first offset`() {
        // Europe/Warsaw: 2026-10-25, clocks go 03:00 → 02:00 (CEST→CET)
        val zone = ZoneId.of("Europe/Warsaw")
        val instant =
            java.time.LocalDate.of(2026, 10, 25)
                .atTime(1, 0)
                .atZone(zone)
                .toInstant()
        val clock = Clock.fixed(instant, zone)

        // 2:30 AM occurs twice: once in CEST (+02), once in CET (+01)
        val alarmTime = LocalTime.of(2, 30)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        assertTrue("Result should be in the future", millis > clock.millis())
        val result = Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(25, result.dayOfMonth)
        assertEquals(2, result.hour)
        assertEquals(30, result.minute)
        assertEquals(0, result.second)
        // Java atZone picks the first occurrence (summer time, +02:00)
        assertEquals("+02:00", result.offset.toString())
    }

    @Test
    fun `alarm at 23_59_59 edge with second precision`() {
        val clock = clockAt(23, 58)
        val alarmTime = LocalTime.of(23, 59, 59)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        assertEquals(11, result.dayOfMonth)
        assertEquals(23, result.hour)
        assertEquals(59, result.minute)
        assertEquals(59, result.second)
    }

    @Test
    fun `alarm at 00_00_01 just after midnight`() {
        val clock = clockAt(0, 0)
        val alarmTime = LocalTime.of(0, 0, 1)
        val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)

        val result = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        // 00:00:01 is after 00:00:00, should be same day
        assertEquals(11, result.dayOfMonth)
        assertEquals(0, result.hour)
    }

    @Test
    fun `result always in future across multiple timezones`() {
        val zones = listOf("UTC", "Europe/Warsaw", "America/New_York", "Asia/Tokyo", "Pacific/Auckland")
        for (zoneStr in zones) {
            val zone = ZoneId.of(zoneStr)
            for (nowHour in listOf(0, 6, 12, 18, 23)) {
                val clock = clockAt(nowHour, 0, zone)
                for (alarmHour in listOf(0, 6, 12, 18, 23)) {
                    val alarmTime = LocalTime.of(alarmHour, 0)
                    val millis = AlarmTimeCalculator.nextTriggerMillis(alarmTime, clock)
                    assertTrue(
                        "Alarm should be >= now for zone=$zoneStr, now=$nowHour:00, alarm=$alarmHour:00",
                        millis >= clock.millis(),
                    )
                }
            }
        }
    }
}
