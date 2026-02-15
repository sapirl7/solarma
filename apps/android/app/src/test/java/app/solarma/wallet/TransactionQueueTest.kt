package app.solarma.wallet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for TransactionQueue service.
 * Uses mocked DAO since actual Room DB testing requires instrumented tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionQueueTest {

    private lateinit var mockDao: PendingTransactionDao
    private lateinit var queue: TransactionQueue

    @Before
    fun setup() {
        mockDao = mock()
        queue = TransactionQueue(mockDao)
    }

    @Test
    fun `enqueue creates pending transaction`() = runTest {
        // Given
        val expectedId = 42L
        whenever(mockDao.insert(any())).thenReturn(expectedId)

        // When
        val id = queue.enqueue("CREATE_ALARM", alarmId = 100L)

        // Then
        assertEquals(expectedId, id)
        verify(mockDao).insert(argThat { tx ->
            tx.type == "CREATE_ALARM" &&
            tx.alarmId == 100L &&
            tx.status == "PENDING" &&
            tx.retryCount == 0
        })
    }

    @Test
    fun `markConfirmed updates status to CONFIRMED`() = runTest {
        // When
        queue.markConfirmed(123L)

        // Then
        verify(mockDao).updateStatus(
            eq(123L),
            eq("CONFIRMED"),
            isNull(),
            any()
        )
    }

    @Test
    fun `markFailed updates status and increments retry`() = runTest {
        // When
        queue.markFailed(456L, "Network timeout")

        // Then
        verify(mockDao).updateStatus(
            eq(456L),
            eq("FAILED"),
            eq("Network timeout"),
            any()
        )
        verify(mockDao).incrementRetry(eq(456L))
    }

    @Test
    fun `markPendingRetry keeps status PENDING for retry`() = runTest {
        // When
        queue.markPendingRetry(789L, "Signing cancelled")

        // Then
        verify(mockDao).updateStatus(
            eq(789L),
            eq("PENDING"),
            eq("Signing cancelled"),
            any()
        )
        verify(mockDao).incrementRetry(eq(789L))
    }

    @Test
    fun `hasActive returns true when pending transactions exist`() = runTest {
        // Given
        whenever(mockDao.countActiveByTypeAndAlarm("CLAIM", 100L)).thenReturn(2)

        // When
        val result = queue.hasActive("CLAIM", 100L)

        // Then
        assertTrue(result)
    }

    @Test
    fun `hasActive returns false when no pending transactions`() = runTest {
        // Given
        whenever(mockDao.countActiveByTypeAndAlarm("CLAIM", 200L)).thenReturn(0)

        // When
        val result = queue.hasActive("CLAIM", 200L)

        // Then
        assertFalse(result)
    }

    @Test
    fun `getPendingTransactions returns flow from DAO`() = runTest {
        // Given
        val transactions = listOf(
            PendingTransaction(id = 1, type = "CREATE_ALARM", alarmId = 100L),
            PendingTransaction(id = 2, type = "CLAIM", alarmId = 200L)
        )
        whenever(mockDao.getPendingTransactions()).thenReturn(flowOf(transactions))

        // When
        val flow = queue.getPendingTransactions()

        // Then
        verify(mockDao).getPendingTransactions()
    }

    // =========================================================================
    // PendingTransaction Entity Tests
    // =========================================================================

    @Test
    fun `PendingTransaction defaults are correct`() {
        val tx = PendingTransaction(type = "SNOOZE", alarmId = 500L)

        assertEquals(0L, tx.id)
        assertEquals("SNOOZE", tx.type)
        assertEquals(500L, tx.alarmId)
        assertEquals("PENDING", tx.status)
        assertEquals(0, tx.retryCount)
        assertNull(tx.lastError)
        assertNull(tx.lastAttemptAt)
        assertTrue(tx.createdAt > 0)
    }

    @Test
    fun `Transaction type constants match expected values`() {
        // These should match TransactionProcessor expectations
        val validTypes = listOf("CREATE_ALARM", "CLAIM", "SNOOZE", "SLASH", "EMERGENCY_REFUND")

        validTypes.forEach { type ->
            val tx = PendingTransaction(type = type, alarmId = 1L)
            assertEquals(type, tx.type)
        }
    }
}
