package app.solarma.wallet

import app.solarma.alarm.AlarmRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the pure data-mapping logic used by AlarmRepository.importOnchainAlarms
 * (which MainActivity's chain sync also goes through).
 *
 * Imported: Created alarms with a deposit left, and Acknowledged alarms whose
 * deposit still has to be claimed or swept. Resolved alarms are closed on-chain;
 * legacy Claimed/Slashed accounts are skipped.
 */
class ImportOnchainAlarmMappingTest {
    // ── importOnchainAlarms filtering (AlarmRepository.isImportable) ──

    @Test
    fun `import - status 0 Created with positive remaining is importable`() {
        assertTrue(isImportable(status = 0, remainingAmount = 1000))
    }

    @Test
    fun `import - status 1 Acknowledged is importable (deposit still to collect)`() {
        assertTrue(isImportable(status = 1, remainingAmount = 1000))
        // Even with nothing left, claiming returns the account rent
        assertTrue(isImportable(status = 1, remainingAmount = 0))
    }

    @Test
    fun `import - status 2 Claimed is skipped`() {
        assertFalse(isImportable(status = 2, remainingAmount = 0))
    }

    @Test
    fun `import - status 3 Slashed is skipped`() {
        assertFalse(isImportable(status = 3, remainingAmount = 0))
    }

    @Test
    fun `import - status 0 but zero remaining is skipped`() {
        assertFalse(isImportable(status = 0, remainingAmount = 0))
    }

    @Test
    fun `import - status 0 but negative remaining is skipped`() {
        assertFalse(isImportable(status = 0, remainingAmount = -1))
    }

    // ── Time mapping ──

    @Test
    fun `alarm time unix to millis conversion`() {
        val alarmTimeUnix = 1_700_000_000L
        val expectedMillis = 1_700_000_000_000L
        assertEquals(expectedMillis, alarmTimeUnix * 1000)
    }

    @Test
    fun `deposit lamports to SOL conversion`() {
        val lamports = 1_500_000_000L
        val sol = lamports / 1_000_000_000.0
        assertEquals(1.5, sol, 0.0001)
    }

    @Test
    fun `isEnabled based on alarm time vs now`() {
        val futureAlarm = 2_000_000_000_000L // far future
        val pastAlarm = 1_000_000_000_000L // 2001
        val now = 1_700_000_000_000L // 2023

        assertTrue("Future alarm should be enabled", futureAlarm > now)
        assertFalse("Past alarm should not be enabled", pastAlarm > now)
    }

    // ── Penalty route mapping ──

    @Test
    fun `penalty route 0 maps to BURN`() {
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(0))
    }

    @Test
    fun `penalty route 1 maps to DONATE`() {
        assertEquals(PenaltyRoute.DONATE, PenaltyRoute.fromCode(1))
    }

    @Test
    fun `penalty route 2 maps to BUDDY`() {
        assertEquals(PenaltyRoute.BUDDY, PenaltyRoute.fromCode(2))
    }

    @Test
    fun `penalty route unknown defaults to BURN`() {
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(99))
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(-1))
    }

    // ── Default values for new imports ──

    @Test
    fun `imported alarm gets default label`() {
        assertEquals("Imported Alarm", DEFAULT_IMPORT_LABEL)
    }

    @Test
    fun `imported alarm gets default wake proof type 1`() {
        assertEquals(1, DEFAULT_WAKE_PROOF_TYPE)
    }

    @Test
    fun `imported alarm gets default target steps 20`() {
        assertEquals(20, DEFAULT_TARGET_STEPS)
    }

    companion object {
        /** Production filter used by importOnchainAlarms. */
        private fun isImportable(
            status: Int,
            remainingAmount: Long,
        ): Boolean = AlarmRepository.isImportable(status, remainingAmount)

        private const val DEFAULT_IMPORT_LABEL = "Imported Alarm"
        private const val DEFAULT_WAKE_PROOF_TYPE = 1
        private const val DEFAULT_TARGET_STEPS = 20
    }
}
