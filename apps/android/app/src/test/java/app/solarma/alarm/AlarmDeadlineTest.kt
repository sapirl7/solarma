package app.solarma.alarm

import app.solarma.data.local.AlarmEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for on-chain deadline tracking and deadline-aware snoozing.
 */
class AlarmDeadlineTest {
    private val now = 1_700_000_000_000L

    private fun alarm(
        hasDeposit: Boolean = true,
        alarmTimeMillis: Long = now,
        onchainDeadline: Long? = null,
    ) = AlarmEntity(
        alarmTimeMillis = alarmTimeMillis,
        hasDeposit = hasDeposit,
        onchainDeadline = onchainDeadline,
    )

    @Test
    fun `deadline uses the stored on-chain value`() {
        val a = alarm(onchainDeadline = now / 1000 + 600)
        assertEquals(now + 600_000, AlarmTiming.deadlineMillis(a))
    }

    @Test
    fun `deadline falls back to alarm time plus grace`() {
        assertEquals(now + AlarmTiming.GRACE_PERIOD_MILLIS, AlarmTiming.deadlineMillis(alarm()))
    }

    @Test
    fun `alarm without deposit always snoozes the full interval`() {
        assertEquals(now + AlarmTiming.SNOOZE_MILLIS, AlarmTiming.snoozeRingMillis(alarm(hasDeposit = false), now))
    }

    @Test
    fun `deposit alarm far from its deadline snoozes the full interval`() {
        val a = alarm(onchainDeadline = now / 1000 + 1_800)
        assertEquals(now + AlarmTiming.SNOOZE_MILLIS, AlarmTiming.snoozeRingMillis(a, now))
    }

    @Test
    fun `deposit alarm never rings later than the safety margin before its deadline`() {
        // 8 minutes left: ring again 3 minutes from now (5 minutes before the deadline)
        val a = alarm(onchainDeadline = now / 1000 + 8 * 60)
        assertEquals(now + 3 * 60_000, AlarmTiming.snoozeRingMillis(a, now))
    }

    @Test
    fun `deposit alarm too close to its deadline cannot be snoozed`() {
        val a = alarm(onchainDeadline = now / 1000 + 5 * 60)
        assertNull(AlarmTiming.snoozeRingMillis(a, now))
    }
}
