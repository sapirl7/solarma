package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PendingTransaction data class.
 * PenaltyRoute and SolarmaTreasury are tested in PenaltyRouteTest.kt.
 */
class TransactionDataClassTest {

    // ── PendingTransaction defaults ──

    @Test
    fun `PendingTransaction default values`() {
        val tx = PendingTransaction(type = "CREATE_ALARM", alarmId = 1L)
        assertEquals(0L, tx.id)
        assertEquals("CREATE_ALARM", tx.type)
        assertEquals("", tx.transactionData)
        assertEquals(1L, tx.alarmId)
        assertEquals(0, tx.retryCount)
        assertNull(tx.lastError)
        assertNull(tx.lastAttemptAt)
        assertEquals("PENDING", tx.status)
    }

    // ── PendingTransaction equality ──

    @Test
    fun `PendingTransaction equality`() {
        val now = 1000L
        val a = PendingTransaction(id = 1, type = "CLAIM", alarmId = 2, status = "CONFIRMED", createdAt = now)
        val b = PendingTransaction(id = 1, type = "CLAIM", alarmId = 2, status = "CONFIRMED", createdAt = now)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `PendingTransaction inequality on id`() {
        val now = 1000L
        val a = PendingTransaction(id = 1, type = "CLAIM", alarmId = 2, createdAt = now)
        val b = PendingTransaction(id = 2, type = "CLAIM", alarmId = 2, createdAt = now)
        assertNotEquals(a, b)
    }

    // ── PendingTransaction copy ──

    @Test
    fun `PendingTransaction copy updates status`() {
        val tx = PendingTransaction(type = "SNOOZE", alarmId = 1)
        val updated = tx.copy(status = "CONFIRMED", retryCount = 3)
        assertEquals("CONFIRMED", updated.status)
        assertEquals(3, updated.retryCount)
        assertEquals("PENDING", tx.status) // original unchanged
    }

    // ── PendingTransaction type coverage ──

    @Test
    fun `PendingTransaction all transaction types`() {
        val types = listOf("CREATE_ALARM", "CLAIM", "ACK_AWAKE", "SNOOZE", "SLASH", "EMERGENCY_REFUND")
        for (t in types) {
            val tx = PendingTransaction(type = t, alarmId = 1)
            assertEquals(t, tx.type)
        }
    }

    @Test
    fun `PendingTransaction all statuses`() {
        val statuses = listOf("PENDING", "SENDING", "CONFIRMED", "FAILED")
        for (s in statuses) {
            val tx = PendingTransaction(type = "CLAIM", alarmId = 1, status = s)
            assertEquals(s, tx.status)
        }
    }

    // ── PendingTransaction with error ──

    @Test
    fun `PendingTransaction with lastError`() {
        val tx = PendingTransaction(
            type = "CREATE_ALARM",
            alarmId = 1,
            status = "FAILED",
            lastError = "Max retries exceeded"
        )
        assertEquals("FAILED", tx.status)
        assertEquals("Max retries exceeded", tx.lastError)
    }

    // ── PendingTransaction timestamps ──

    @Test
    fun `PendingTransaction with timestamps`() {
        val now = System.currentTimeMillis()
        val tx = PendingTransaction(
            type = "CLAIM",
            alarmId = 1,
            lastAttemptAt = now,
            createdAt = now
        )
        assertEquals(now, tx.lastAttemptAt)
        assertEquals(now, tx.createdAt)
    }

    // ── PendingTransaction retry progression ──

    @Test
    fun `PendingTransaction retry count progression`() {
        var tx = PendingTransaction(type = "SNOOZE", alarmId = 1)
        for (i in 1..5) {
            tx = tx.copy(retryCount = i)
            assertEquals(i, tx.retryCount)
        }
    }

    // ── PendingTransaction nullable alarmId ──

    @Test
    fun `PendingTransaction nullable alarmId`() {
        val tx = PendingTransaction(type = "SLASH", alarmId = null)
        assertNull(tx.alarmId)
    }

    // ── PendingTransaction transactionData ──

    @Test
    fun `PendingTransaction transactionData field`() {
        val tx = PendingTransaction(type = "CLAIM", alarmId = 1, transactionData = "base64data")
        assertEquals("base64data", tx.transactionData)
    }

    // ── PendingTransaction toString ──

    @Test
    fun `PendingTransaction toString contains type and alarmId`() {
        val tx = PendingTransaction(type = "SLASH", alarmId = 42)
        val str = tx.toString()
        assertTrue(str.contains("SLASH"))
        assertTrue(str.contains("42"))
    }

    // ── PendingTransaction destructuring ──

    @Test
    fun `PendingTransaction destructuring`() {
        val tx = PendingTransaction(
            id = 1,
            type = "CLAIM",
            transactionData = "data",
            alarmId = 2,
            retryCount = 3,
            lastError = "err",
            createdAt = 1000L,
            lastAttemptAt = 2000L,
            status = "FAILED"
        )
        assertEquals(1L, tx.id)
        assertEquals("CLAIM", tx.type)
        assertEquals("data", tx.transactionData)
        assertEquals(2L, tx.alarmId)
        assertEquals(3, tx.retryCount)
        assertEquals("err", tx.lastError)
        assertEquals(1000L, tx.createdAt)
        assertEquals(2000L, tx.lastAttemptAt)
        assertEquals("FAILED", tx.status)
    }
}
