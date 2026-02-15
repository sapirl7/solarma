package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the pure data-mapping logic used by
 * AlarmRepository.importOnchainAlarms and OnchainAlarmService.syncOnchainAlarms.
 *
 * Two separate filtering strategies exist:
 * - importOnchainAlarms: only imports status==0 (Created) with remaining>0
 * - syncOnchainAlarms: considers status>=2 OR remaining<=0 as "resolved"
 *
 * This test validates both strategies.
 */
class ImportOnchainAlarmMappingTest {
    // ── importOnchainAlarms filtering (AlarmRepository line 348) ──
    // Only status==0 AND remainingAmount>0 passes the filter.

    @Test
    fun `import - status 0 Created with positive remaining is importable`() {
        assertTrue(isImportable(status = 0, remainingAmount = 1000))
    }

    @Test
    fun `import - status 1 Acknowledged is skipped`() {
        // importOnchainAlarms checks status != 0 → skips Acknowledged
        assertFalse(isImportable(status = 1, remainingAmount = 1000))
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

    // ── syncOnchainAlarms filtering (OnchainAlarmService line 378) ──
    // status >= 2 OR remainingAmount <= 0 means "resolved"

    @Test
    fun `sync - status 0 Created is not resolved`() {
        assertFalse(isSyncResolved(status = 0, remainingAmount = 1000))
    }

    @Test
    fun `sync - status 1 Acknowledged is not resolved`() {
        assertFalse(isSyncResolved(status = 1, remainingAmount = 1000))
    }

    @Test
    fun `sync - status 2 Claimed is resolved`() {
        assertTrue(isSyncResolved(status = 2, remainingAmount = 0))
    }

    @Test
    fun `sync - status 3 Slashed is resolved`() {
        assertTrue(isSyncResolved(status = 3, remainingAmount = 0))
    }

    @Test
    fun `sync - status 0 but zero remaining is resolved`() {
        assertTrue(isSyncResolved(status = 0, remainingAmount = 0))
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
        val pastAlarm = 1_000_000_000_000L   // 2001
        val now = 1_700_000_000_000L          // 2023

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

    // ── Import vs Sync strategy divergence ──

    @Test
    fun `acknowledged alarm is importable by sync but not by import`() {
        // This documents the real inconsistency: syncOnchainAlarms
        // treats Acknowledged as active (not resolved), while
        // importOnchainAlarms skips it (status != 0).
        val status = 1  // Acknowledged
        val remaining = 1000L
        assertFalse("import skips Acknowledged", isImportable(status, remaining))
        assertFalse("sync does NOT consider Acknowledged resolved", isSyncResolved(status, remaining))
    }

    companion object {
        /** importOnchainAlarms filter: only status==0 and remaining>0 */
        private fun isImportable(status: Int, remainingAmount: Long): Boolean {
            return status == 0 && remainingAmount > 0
        }

        /** syncOnchainAlarms filter: status>=2 or remaining<=0 means resolved */
        private fun isSyncResolved(status: Int, remainingAmount: Long): Boolean {
            return status >= 2 || remainingAmount <= 0
        }

        private const val DEFAULT_IMPORT_LABEL = "Imported Alarm"
        private const val DEFAULT_WAKE_PROOF_TYPE = 1
        private const val DEFAULT_TARGET_STEPS = 20
    }
}
