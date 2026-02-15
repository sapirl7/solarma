package app.solarma.wallet

import app.solarma.wallet.OnchainAlarmService.ConfirmationResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OnchainAlarmService inner types:
 * - ConfirmationResult sealed class (Confirmed/Failed/Pending)
 * - PendingConfirmationException
 */
class OnchainAlarmServiceTest {
    // ── ConfirmationResult sealed class ──

    @Test
    fun `Confirmed is a singleton`() {
        assertSame(ConfirmationResult.Confirmed, ConfirmationResult.Confirmed)
    }

    @Test
    fun `Pending is a singleton`() {
        assertSame(ConfirmationResult.Pending, ConfirmationResult.Pending)
    }

    @Test
    fun `Failed holds error message`() {
        val result = ConfirmationResult.Failed("InvalidAlarmState")
        assertEquals("InvalidAlarmState", result.error)
    }

    @Test
    fun `Failed equality by error message`() {
        val a = ConfirmationResult.Failed("timeout")
        val b = ConfirmationResult.Failed("timeout")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Failed inequality for different errors`() {
        val a = ConfirmationResult.Failed("timeout")
        val b = ConfirmationResult.Failed("DeadlinePassed")
        assertNotEquals(a, b)
    }

    @Test
    fun `all subtypes are distinct`() {
        val confirmed: ConfirmationResult = ConfirmationResult.Confirmed
        val failed: ConfirmationResult = ConfirmationResult.Failed("err")
        val pending: ConfirmationResult = ConfirmationResult.Pending

        assertNotEquals(confirmed, failed)
        assertNotEquals(confirmed, pending)
        assertNotEquals(failed, pending)
    }

    @Test
    fun `exhaustive when works`() {
        val results = listOf(
            ConfirmationResult.Confirmed,
            ConfirmationResult.Failed("err"),
            ConfirmationResult.Pending
        )
        for (result in results) {
            val label = when (result) {
                is ConfirmationResult.Confirmed -> "confirmed"
                is ConfirmationResult.Failed -> "failed: ${result.error}"
                is ConfirmationResult.Pending -> "pending"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    @Test
    fun `Failed copy changes error`() {
        val original = ConfirmationResult.Failed("a")
        val copy = original.copy(error = "b")
        assertEquals("b", copy.error)
        assertNotEquals(original, copy)
    }

    @Test
    fun `Failed toString contains error`() {
        val f = ConfirmationResult.Failed("DeadlinePassed")
        assertTrue(f.toString().contains("DeadlinePassed"))
    }

    // ── PendingConfirmationException ──

    @Test
    fun `PendingConfirmationException holds signature and pda`() {
        val ex = OnchainAlarmService.PendingConfirmationException(
            signature = "5abc123",
            pda = "7xyz456"
        )
        assertEquals("5abc123", ex.signature)
        assertEquals("7xyz456", ex.pda)
        assertEquals("Transaction pending confirmation", ex.message)
    }

    @Test
    fun `PendingConfirmationException is an Exception`() {
        val ex = OnchainAlarmService.PendingConfirmationException("sig", "pda")
        assertTrue(ex is Exception)
    }
}
