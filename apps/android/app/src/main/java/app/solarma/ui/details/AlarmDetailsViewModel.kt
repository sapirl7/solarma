package app.solarma.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.alarm.AlarmRepository
import app.solarma.data.local.AlarmEntity
import app.solarma.wallet.OnchainAlarmService
import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for alarm details screen.
 */
@HiltViewModel
class AlarmDetailsViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val onchainAlarmService: OnchainAlarmService,
    private val pendingTransactionDao: PendingTransactionDao
) : ViewModel() {

    private val _alarm = MutableStateFlow<AlarmEntity?>(null)
    val alarm: StateFlow<AlarmEntity?> = _alarm.asStateFlow()

    private val _refundState = MutableStateFlow<RefundState>(RefundState.Idle)
    val refundState: StateFlow<RefundState> = _refundState.asStateFlow()

    private val _pendingCreate = MutableStateFlow(false)
    val pendingCreate: StateFlow<Boolean> = _pendingCreate.asStateFlow()
    private var pendingJob: Job? = null

    fun loadAlarm(id: Long) {
        viewModelScope.launch {
            _alarm.value = alarmRepository.getAlarm(id)
        }

        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            pendingTransactionDao.observeActiveCount("CREATE_ALARM", id).collect { count ->
                _pendingCreate.value = count > 0
            }
        }
    }

    /**
     * Request emergency refund for deposit.
     */
    fun requestEmergencyRefund(activityResultSender: ActivityResultSender) {
        val currentAlarm = _alarm.value ?: return
        if (!currentAlarm.hasDeposit || currentAlarm.onchainPubkey == null) return

        viewModelScope.launch {
            _refundState.value = RefundState.Processing

            onchainAlarmService.emergencyRefund(activityResultSender, currentAlarm)
                .onSuccess { signature ->
                    // Update local alarm and stats
                    alarmRepository.recordEmergencyRefund(currentAlarm)
                    // Record confirmed transaction for history
                    pendingTransactionDao.insert(
                        PendingTransaction(
                            type = "EMERGENCY_REFUND",
                            alarmId = currentAlarm.id,
                            status = "CONFIRMED",
                            lastAttemptAt = System.currentTimeMillis()
                        )
                    )
                    _alarm.value = currentAlarm.copy(hasDeposit = false, depositLamports = 0, depositAmount = 0.0)
                    _refundState.value = RefundState.Success(signature)
                }
                .onFailure { e ->
                    _refundState.value = RefundState.Error(e.message ?: "Refund failed")
                }
        }
    }

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    fun deleteAlarm() {
        viewModelScope.launch {
            _alarm.value?.let { alarm ->
                if (alarm.hasDeposit && alarm.depositLamports > 0) {
                    _deleteState.value = DeleteState.BlockedByDeposit
                    return@launch
                }
                alarmRepository.deleteAlarm(alarm.id)
                _deleteState.value = DeleteState.Deleted
            }
        }
    }

    fun resetDeleteState() {
        _deleteState.value = DeleteState.Idle
    }

    fun resetRefundState() {
        _refundState.value = RefundState.Idle
    }

    /**
     * Slash (resolve) expired alarm — releases deposit per penalty route.
     */
    fun requestSlash(activityResultSender: ActivityResultSender) {
        val currentAlarm = _alarm.value ?: return
        if (!currentAlarm.hasDeposit || currentAlarm.onchainPubkey == null) return

        viewModelScope.launch {
            _refundState.value = RefundState.Processing

            onchainAlarmService.slashAlarm(activityResultSender, currentAlarm)
                .onSuccess { signature ->
                    pendingTransactionDao.insert(
                        PendingTransaction(
                            type = "SLASH",
                            alarmId = currentAlarm.id,
                            status = "CONFIRMED",
                            lastAttemptAt = System.currentTimeMillis()
                        )
                    )
                    _alarm.value = currentAlarm.copy(hasDeposit = false, depositLamports = 0, depositAmount = 0.0)
                    _refundState.value = RefundState.Success(signature)
                }
                .onFailure { e ->
                    _refundState.value = RefundState.Error(e.message ?: "Slash failed")
                }
        }
    }
}

sealed class RefundState {
    object Idle : RefundState()
    object Processing : RefundState()
    data class Success(val signature: String) : RefundState()
    data class Error(val message: String) : RefundState()
}

sealed class DeleteState {
    object Idle : DeleteState()
    object Deleted : DeleteState()
    object BlockedByDeposit : DeleteState()
}
