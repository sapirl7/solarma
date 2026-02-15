package app.solarma.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import app.solarma.wallet.WalletConnectionState
import app.solarma.wallet.WalletManager
import app.solarma.wallet.SolanaRpcClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the home screen.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val alarmDao: AlarmDao,
    private val statsDao: StatsDao,
    private val walletManager: WalletManager,
    private val rpcClient: SolanaRpcClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeAlarms()
        observeStats()
        observeWallet()
    }

    private fun observeAlarms() {
        viewModelScope.launch {
            alarmDao.getAllAlarms().collect { alarms ->
                _uiState.update { it.copy(alarms = alarms) }
            }
        }
    }

    private fun observeStats() {
        viewModelScope.launch {
            statsDao.getStats().collect { stats ->
                if (stats != null) {
                    _uiState.update {
                        it.copy(
                            currentStreak = stats.currentStreak,
                            totalWakes = stats.totalWakes,
                            savedSol = stats.totalSaved / 1_000_000_000.0
                        )
                    }
                }
            }
        }
    }

    private fun observeWallet() {
        viewModelScope.launch {
            walletManager.connectionState.collect { state ->
                when (state) {
                    is WalletConnectionState.Connected -> {
                        _uiState.update {
                            it.copy(
                                walletConnected = true,
                                walletAddress = state.publicKeyBase58
                            )
                        }
                        // Fetch balance
                        fetchBalance(state.publicKey)
                    }
                    else -> {
                        _uiState.update {
                            it.copy(walletConnected = false, walletBalance = null, walletAddress = null)
                        }
                    }
                }
            }
        }
    }

    private fun fetchBalance(publicKey: ByteArray) {
        viewModelScope.launch {
            val pubkeyBase58 = publicKey.toBase58()
            rpcClient.getBalance(pubkeyBase58).onSuccess { lamports ->
                _uiState.update {
                    it.copy(walletBalance = lamports / 1_000_000_000.0)
                }
            }
        }
    }

    fun connectWallet(activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            walletManager.connect(activityResultSender).fold(
                onSuccess = { publicKey ->
                    // Connection state is observed automatically
                    _uiState.update { it.copy(isLoading = false) }
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() }
                        ?: "Wallet connection failed"
                    _uiState.update { it.copy(isLoading = false, errorMessage = message) }
                }
            )
        }
    }

    /**
     * Toggle alarm enabled/disabled.
     */
    fun setAlarmEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch {
            alarmDao.setEnabled(alarmId, enabled)
        }
    }

    /**
     * Delete an alarm.
     * Blocks deletion if alarm has an active onchain deposit.
     */
    fun deleteAlarm(alarmId: Long) {
        viewModelScope.launch {
            val alarm = alarmDao.getById(alarmId)
            if (alarm?.hasDeposit == true) {
                // Alarm has active onchain deposit - cannot delete without refund
                _uiState.update { it.copy(
                    errorMessage = "Cannot delete alarm with active deposit. Cancel and refund first."
                ) }
                return@launch
            }
            alarmDao.deleteById(alarmId)
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun ByteArray.toBase58(): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger(1, this)
        val sb = StringBuilder()
        while (num > java.math.BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(java.math.BigInteger.valueOf(58))
            sb.insert(0, alphabet[r.toInt()])
            num = q
        }
        for (b in this) {
            if (b.toInt() == 0) sb.insert(0, '1') else break
        }
        return sb.toString()
    }
}

/**
 * UI state for home screen.
 */
data class HomeUiState(
    val alarms: List<AlarmEntity> = emptyList(),
    val currentStreak: Int = 0,
    val totalWakes: Int = 0,
    val savedSol: Double = 0.0,
    val walletConnected: Boolean = false,
    val walletAddress: String? = null,
    val walletBalance: Double? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
