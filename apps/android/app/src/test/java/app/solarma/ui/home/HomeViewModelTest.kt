package app.solarma.ui.home

import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import app.solarma.wallet.SolanaRpcClient
import app.solarma.wallet.WalletConnectionState
import app.solarma.wallet.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for [HomeViewModel].
 *
 * Tests public API: setAlarmEnabled, deleteAlarm, clearError.
 * Verifies DAO interactions and UI state transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var alarmDao: AlarmDao
    private lateinit var statsDao: StatsDao
    private lateinit var walletManager: WalletManager
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        alarmDao =
            mock {
                on { getAllAlarms() } doReturn flowOf(emptyList())
            }
        statsDao =
            mock {
                on { getStats() } doReturn flowOf(null)
            }
        walletManager =
            mock {
                on { connectionState } doReturn MutableStateFlow(WalletConnectionState.Disconnected)
            }
        rpcClient = mock()

        viewModel = HomeViewModel(alarmDao, statsDao, walletManager, rpcClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── observeAlarms ──────────────────────────────────────

    @Test
    fun `initial state has empty alarm list`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(viewModel.uiState.value.alarms.isEmpty())
        }

    @Test
    fun `alarms are loaded from dao on init`() =
        runTest {
            val alarms =
                listOf(
                    AlarmEntity(id = 1, alarmTimeMillis = 1000, label = "Test"),
                    AlarmEntity(id = 2, alarmTimeMillis = 2000, label = "Test 2"),
                )
            whenever(alarmDao.getAllAlarms()).thenReturn(flowOf(alarms))

            val vm = HomeViewModel(alarmDao, statsDao, walletManager, rpcClient)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, vm.uiState.value.alarms.size)
            assertEquals("Test", vm.uiState.value.alarms[0].label)
        }

    // ── observeStats ───────────────────────────────────────

    @Test
    fun `stats are loaded from dao`() =
        runTest {
            val stats =
                StatsEntity(
                    currentStreak = 5,
                    totalWakes = 42,
                    // 3 SOL
                    totalSaved = 3_000_000_000L,
                )
            whenever(statsDao.getStats()).thenReturn(flowOf(stats))

            val vm = HomeViewModel(alarmDao, statsDao, walletManager, rpcClient)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(5, vm.uiState.value.currentStreak)
            assertEquals(42, vm.uiState.value.totalWakes)
            assertEquals(3.0, vm.uiState.value.savedSol, 0.001)
        }

    @Test
    fun `null stats keeps default zeroes`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, viewModel.uiState.value.currentStreak)
            assertEquals(0, viewModel.uiState.value.totalWakes)
            assertEquals(0.0, viewModel.uiState.value.savedSol, 0.001)
        }

    // ── setAlarmEnabled ────────────────────────────────────

    @Test
    fun `setAlarmEnabled calls dao setEnabled`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.setAlarmEnabled(42L, false)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(alarmDao).setEnabled(42L, false)
        }

    @Test
    fun `setAlarmEnabled with true enables alarm`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.setAlarmEnabled(1L, true)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(alarmDao).setEnabled(1L, true)
        }

    // ── deleteAlarm ────────────────────────────────────────

    @Test
    fun `deleteAlarm removes alarm without deposit`() =
        runTest {
            val alarm = AlarmEntity(id = 5, alarmTimeMillis = 1000, onchainPubkey = null)
            whenever(alarmDao.getById(5L)).thenReturn(alarm)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteAlarm(5L)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(alarmDao).deleteById(5L)
        }

    @Test
    fun `deleteAlarm with onchain deposit shows error`() =
        runTest {
            val alarm =
                AlarmEntity(
                    id = 7, alarmTimeMillis = 1000,
                    hasDeposit = true,
                    onchainPubkey = "9xQeWvG816bUx9EPjHmaT1E4aLGb2P",
                )
            whenever(alarmDao.getById(7L)).thenReturn(alarm)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteAlarm(7L)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(alarmDao, never()).deleteById(any())
            assertNotNull(viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.errorMessage!!.contains("refund"))
        }

    // ── clearError ─────────────────────────────────────────

    @Test
    fun `clearError resets error message`() =
        runTest {
            // Set an error first via deleteAlarm with deposit
            val alarm =
                AlarmEntity(
                    id = 1, alarmTimeMillis = 1000,
                    hasDeposit = true,
                    onchainPubkey = "SomeKey",
                )
            whenever(alarmDao.getById(1L)).thenReturn(alarm)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteAlarm(1L)
            testDispatcher.scheduler.advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.errorMessage)

            viewModel.clearError()
            assertNull(viewModel.uiState.value.errorMessage)
        }

    // ── wallet state ───────────────────────────────────────

    @Test
    fun `wallet disconnected sets walletConnected false`() =
        runTest {
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.walletConnected)
            assertNull(viewModel.uiState.value.walletBalance)
        }
}
