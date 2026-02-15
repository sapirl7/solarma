package app.solarma.alarm

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AlarmTiming constants.
 * These constants MUST match Rust on-chain constants for correct behavior.
 *
 * Rust constants.rs:
 *   DEFAULT_GRACE_PERIOD = 1800
 *   DEFAULT_SNOOZE_EXTENSION_SECONDS = 300
 */
class AlarmTimingTest {
    // =========================================================================
    // Grace period constants
    // =========================================================================

    @Test
    fun `grace period seconds matches Rust DEFAULT_GRACE_PERIOD`() {
        assertEquals(1800L, AlarmTiming.GRACE_PERIOD_SECONDS)
    }

    @Test
    fun `grace period millis is seconds times 1000`() {
        assertEquals(
            AlarmTiming.GRACE_PERIOD_SECONDS * 1000L,
            AlarmTiming.GRACE_PERIOD_MILLIS
        )
    }

    @Test
    fun `grace period is 30 minutes`() {
        assertEquals(30L * 60L, AlarmTiming.GRACE_PERIOD_SECONDS)
    }

    // =========================================================================
    // Snooze extension constants
    // =========================================================================

    @Test
    fun `snooze minutes matches Rust DEFAULT_SNOOZE_EXTENSION`() {
        // Rust: DEFAULT_SNOOZE_EXTENSION_SECONDS = 300 = 5 minutes
        assertEquals(5, AlarmTiming.SNOOZE_MINUTES)
    }

    @Test
    fun `snooze millis is minutes times 60000`() {
        assertEquals(
            AlarmTiming.SNOOZE_MINUTES.toLong() * 60 * 1000L,
            AlarmTiming.SNOOZE_MILLIS
        )
    }

    @Test
    fun `snooze millis is 300000 ms`() {
        assertEquals(300_000L, AlarmTiming.SNOOZE_MILLIS)
    }

    // =========================================================================
    // Cross-platform parity
    // =========================================================================

    @Test
    fun `grace period longer than snooze extension`() {
        // Must allow at least one snooze within grace period
        assertTrue(
            "Grace must be >= snooze extension",
            AlarmTiming.GRACE_PERIOD_MILLIS >= AlarmTiming.SNOOZE_MILLIS
        )
    }

    @Test
    fun `at least 1 snooze fits in grace period`() {
        val snoozesPerGrace = AlarmTiming.GRACE_PERIOD_MILLIS / AlarmTiming.SNOOZE_MILLIS
        assertTrue("At least 1 snooze must fit in grace period", snoozesPerGrace >= 1)
    }

    @Test
    fun `constants are positive`() {
        assertTrue(AlarmTiming.GRACE_PERIOD_SECONDS > 0)
        assertTrue(AlarmTiming.GRACE_PERIOD_MILLIS > 0)
        assertTrue(AlarmTiming.SNOOZE_MINUTES > 0)
        assertTrue(AlarmTiming.SNOOZE_MILLIS > 0)
    }
}
