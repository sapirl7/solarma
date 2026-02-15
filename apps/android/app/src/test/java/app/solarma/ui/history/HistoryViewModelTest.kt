package app.solarma.ui.history

import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for HistoryViewModel.
 * Uses UnconfinedTestDispatcher so stateIn collection dispatches eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeDao: FakePendingTransactionDao
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakePendingTransactionDao()
        viewModel = HistoryViewModel(fakeDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `transactions starts with empty list`() = runTest {
        assertTrue(viewModel.transactions.value.isEmpty())
    }

    @Test
    fun `transactions reflects DAO emissions`() = runTest {
        val tx = makeTx(id = 1, type = "CLAIM")
        fakeDao.emit(listOf(tx))
        assertEquals(1, viewModel.transactions.value.size)
        assertEquals("CLAIM", viewModel.transactions.value[0].type)
    }

    @Test
    fun `deleteTransaction calls DAO delete`() = runTest {
        val tx = makeTx(id = 1, type = "CLAIM")
        viewModel.deleteTransaction(tx)
        assertEquals(1, fakeDao.deletedTransactions.size)
        assertEquals(tx, fakeDao.deletedTransactions[0])
    }

    @Test
    fun `multiple emissions update flow`() = runTest {
        fakeDao.emit(listOf(makeTx(1, "CLAIM")))
        assertEquals(1, viewModel.transactions.value.size)

        fakeDao.emit(listOf(makeTx(1, "CLAIM"), makeTx(2, "SLASH")))
        assertEquals(2, viewModel.transactions.value.size)
    }

    @Test
    fun `empty emission clears list`() = runTest {
        fakeDao.emit(listOf(makeTx(1, "CLAIM")))
        assertEquals(1, viewModel.transactions.value.size)

        fakeDao.emit(emptyList())
        assertTrue(viewModel.transactions.value.isEmpty())
    }

    // ── Helpers ──

    private fun makeTx(id: Long, type: String) = PendingTransaction(
        id = id,
        type = type,
        alarmId = 100L,
        status = "CONFIRMED",
        lastError = null,
        retryCount = 0,
        createdAt = System.currentTimeMillis(),
        lastAttemptAt = null
    )

    /**
     * Fake DAO that returns a controllable flow and records delete calls.
     */
    private class FakePendingTransactionDao : PendingTransactionDao {
        private val flow = MutableStateFlow<List<PendingTransaction>>(emptyList())
        val deletedTransactions = mutableListOf<PendingTransaction>()

        fun emit(list: List<PendingTransaction>) {
            flow.value = list
        }

        override fun getAllTransactions(): Flow<List<PendingTransaction>> = flow
        override fun getPendingTransactions(): Flow<List<PendingTransaction>> = flow
        override suspend fun getById(id: Long): PendingTransaction? = null
        override suspend fun insert(tx: PendingTransaction): Long = tx.id
        override suspend fun update(tx: PendingTransaction) {}
        override suspend fun delete(tx: PendingTransaction) {
            deletedTransactions.add(tx)
        }
        override suspend fun updateStatus(id: Long, status: String, error: String?, timestamp: Long) {}
        override suspend fun incrementRetry(id: Long) {}
        override suspend fun countActiveByTypeAndAlarm(type: String, alarmId: Long): Int = 0
        override suspend fun deleteConfirmed() {}
        override suspend fun getPendingCount(): Int = 0
        override fun observeActiveCount(type: String, alarmId: Long): Flow<Int> = MutableStateFlow(0)
    }
}
