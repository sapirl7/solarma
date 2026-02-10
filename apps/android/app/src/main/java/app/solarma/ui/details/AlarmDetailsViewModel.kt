package app.solarma.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.alarm.AlarmRepository
import app.solarma.data.local.AlarmEntity
import app.solarma.wallet.PendingTransaction
import app.solarma.wallet.PendingTransactionDao
import app.solarma.wallet.OnchainAlarmService
import app.solarma.wallet.TransactionProcessor
import app.solarma.wallet.TransactionQueue
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * ViewModel for alarm details screen.
 */
@HiltViewModel
class AlarmDetailsViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val onchainAlarmService: OnchainAlarmService,
    private val pendingTransactionDao: PendingTransactionDao,
    private val transactionQueue: TransactionQueue,
    private val transactionProcessor: TransactionProcessor
) : ViewModel() {
    
    private val _alarm = MutableStateFlow<AlarmEntity?>(null)
    val alarm: StateFlow<AlarmEntity?> = _alarm.asStateFlow()
    
    private val _refundState = MutableStateFlow<RefundState>(RefundState.Idle)
    val refundState: StateFlow<RefundState> = _refundState.asStateFlow()

    private val _pendingCreate = MutableStateFlow(false)
    val pendingCreate: StateFlow<Boolean> = _pendingCreate.asStateFlow()
    private var pendingJob: Job? = null

    private val _ackTx = MutableStateFlow<PendingTransaction?>(null)
    val ackTx: StateFlow<PendingTransaction?> = _ackTx.asStateFlow()
    private var ackJob: Job? = null

    private val _claimTx = MutableStateFlow<PendingTransaction?>(null)
    val claimTx: StateFlow<PendingTransaction?> = _claimTx.asStateFlow()
    private var claimJob: Job? = null

    private val _sweepTx = MutableStateFlow<PendingTransaction?>(null)
    val sweepTx: StateFlow<PendingTransaction?> = _sweepTx.asStateFlow()
    private var sweepJob: Job? = null
    
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

        ackJob?.cancel()
        ackJob = viewModelScope.launch {
            combine(
                pendingTransactionDao.observeLatestByTypeAndAlarm("ACK_AWAKE", id),
                pendingTransactionDao.observeLatestByTypeAndAlarm("ACK_AWAKE_ATTESTED", id)
            ) { a, b ->
                listOfNotNull(a, b).maxByOrNull { it.createdAt }
            }.collect { tx ->
                _ackTx.value = tx
            }
        }

        claimJob?.cancel()
        claimJob = viewModelScope.launch {
            pendingTransactionDao.observeLatestByTypeAndAlarm("CLAIM", id).collect { tx ->
                _claimTx.value = tx
            }
        }

        sweepJob?.cancel()
        sweepJob = viewModelScope.launch {
            pendingTransactionDao.observeLatestByTypeAndAlarm("SWEEP_ACKNOWLEDGED", id).collect { tx ->
                _sweepTx.value = tx
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

    fun resendClaim(activityResultSender: ActivityResultSender) {
        val currentAlarm = _alarm.value ?: return
        if (!currentAlarm.hasDeposit || currentAlarm.onchainPubkey == null) return

        viewModelScope.launch {
            if (!transactionQueue.hasActive("CLAIM", currentAlarm.id)) {
                transactionQueue.enqueue("CLAIM", currentAlarm.id)
            }
            transactionProcessor.processPendingTransactionsWithUi(activityResultSender)
        }
    }

    fun requestSweep(activityResultSender: ActivityResultSender) {
        val currentAlarm = _alarm.value ?: return
        if (!currentAlarm.hasDeposit || currentAlarm.onchainPubkey == null) return

        viewModelScope.launch {
            if (!transactionQueue.hasActive("SWEEP_ACKNOWLEDGED", currentAlarm.id)) {
                transactionQueue.enqueue("SWEEP_ACKNOWLEDGED", currentAlarm.id)
            }
            transactionProcessor.processPendingTransactionsWithUi(activityResultSender)
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
