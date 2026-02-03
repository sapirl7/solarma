package app.solarma.ui.create

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.alarm.AlarmRepository
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.settings.dataStore
import app.solarma.wallet.OnchainAlarmService
import app.solarma.wallet.PenaltyRoute
import app.solarma.wallet.SolarmaTreasury
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong
import javax.inject.Inject

/**
 * ViewModel for CreateAlarmScreen.
 * Handles alarm creation: converts UI state → entity → DB + optional onchain deposit.
 */
@HiltViewModel
class CreateAlarmViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val onchainAlarmService: OnchainAlarmService,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "Solarma.CreateAlarmVM"
        private val KEY_NFC_TAG_HASH = stringPreferencesKey("nfc_tag_hash")
        private val KEY_QR_CODE = stringPreferencesKey("qr_code")
        private const val MIN_DEPOSIT_SOL = 0.001
    }
    
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    
    private var pendingAlarm: AlarmEntity? = null
    private var pendingState: CreateAlarmState? = null
    
    /**
     * Save alarm from UI state.
     * Step 1: Save to local DB
     * Step 2: If hasDeposit, return NeedsSigning state (requires ActivityResultSender from UI)
     */
    fun save(state: CreateAlarmState) {
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            
            try {
                // Validate BUDDY address if that route is selected
                if (state.hasDeposit && state.penaltyRoute == 2) {
                    val buddyAddr = state.buddyAddress.trim()
                    if (buddyAddr.isBlank()) {
                        _saveState.value = SaveState.Error("Please enter a buddy wallet address")
                        return@launch
                    }
                    // Validate base58 format (32-44 chars, no invalid chars)
                    if (!buddyAddr.matches(Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$"))) {
                        _saveState.value = SaveState.Error("Invalid buddy address format. Must be a valid Solana address.")
                        return@launch
                    }
                }

                if (state.hasDeposit) {
                    if (state.depositAmount <= 0.0) {
                        _saveState.value = SaveState.Error("Deposit amount must be greater than 0")
                        return@launch
                    }
                    if (state.depositAmount < MIN_DEPOSIT_SOL) {
                        _saveState.value = SaveState.Error("Minimum deposit is $MIN_DEPOSIT_SOL SOL")
                        return@launch
                    }
                }
                
                // Convert LocalTime to next occurrence
                val triggerAtMillis = calculateNextTrigger(state.time)
                val depositLamports = (state.depositAmount * 1_000_000_000L).roundToLong()
                val penaltyDestination = when (state.penaltyRoute) {
                    1 -> SolarmaTreasury.ADDRESS
                    2 -> state.buddyAddress.ifEmpty { null }
                    else -> null
                }
                val onchainAlarmId = if (state.hasDeposit) generateOnchainAlarmId() else null
                
                // Get NFC/QR values from Settings DataStore
                val prefs = context.dataStore.data.first()
                val tagHash = prefs[KEY_NFC_TAG_HASH]
                val qrCode = prefs[KEY_QR_CODE]
                
                // Create entity
                val alarm = AlarmEntity(
                    alarmTimeMillis = triggerAtMillis,
                    label = state.label,
                    isEnabled = true,
                    repeatDays = 0,
                    wakeProofType = state.wakeProofType,
                    targetSteps = state.targetSteps,
                    tagHash = if (state.wakeProofType == 2) tagHash else null,  // NFC
                    qrCode = if (state.wakeProofType == 3) qrCode else null,    // QR
                    hasDeposit = state.hasDeposit,
                    depositAmount = state.depositAmount,
                    depositLamports = if (state.hasDeposit) depositLamports else 0,
                    penaltyRoute = state.penaltyRoute,
                    penaltyDestination = penaltyDestination,
                    onchainAlarmId = onchainAlarmId
                )
                
                // Save and schedule locally
                val id = alarmRepository.createAlarm(alarm)
                val savedAlarm = alarm.copy(id = id)
                
                Log.i(TAG, "Alarm saved locally: id=$id, hasDeposit=${state.hasDeposit}")
                
                // If deposit required, need to sign transaction
                if (state.hasDeposit && state.depositAmount > 0) {
                    pendingAlarm = savedAlarm
                    pendingState = state
                    _saveState.value = SaveState.NeedsSigning(id, state.depositAmount)
                } else {
                    _saveState.value = SaveState.Success(id)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save alarm", e)
                _saveState.value = SaveState.Error(e.message ?: "Failed to save alarm")
            }
        }
    }
    
    /**
     * Sign and submit onchain transaction.
     * Called from UI when ActivityResultSender is available.
     */
    fun signDeposit(activityResultSender: ActivityResultSender) {
        val alarm = pendingAlarm ?: return
        val state = pendingState ?: return
        
        viewModelScope.launch {
            _saveState.value = SaveState.Signing
            
            try {
                val depositLamports = if (alarm.depositLamports > 0) {
                    alarm.depositLamports
                } else {
                    (state.depositAmount * 1_000_000_000L).roundToLong()
                }
                val penaltyRoute = when (state.penaltyRoute) {
                    1 -> PenaltyRoute.DONATE
                    2 -> PenaltyRoute.BUDDY
                    else -> PenaltyRoute.BURN
                }
                val buddyAddress = if (state.penaltyRoute == 2) state.buddyAddress.ifEmpty { null } else null
                
                Log.i(TAG, "Signing deposit: ${state.depositAmount} SOL, route=${penaltyRoute}")
                
                val result = onchainAlarmService.createOnchainAlarm(
                    activityResultSender = activityResultSender,
                    alarm = alarm,
                    depositLamports = depositLamports,
                    penaltyRoute = penaltyRoute,
                    buddyAddress = buddyAddress
                )
                
                result.fold(
                    onSuccess = { pdaAddress ->
                        Log.i(TAG, "Onchain alarm created: pda=$pdaAddress")
                        // Update local alarm with onchain address
                        alarmRepository.updateOnchainAddress(alarm.id, pdaAddress)
                        alarmRepository.recordDeposit(depositLamports)
                        _saveState.value = SaveState.Success(alarm.id)
                    },
                    onFailure = { e ->
                        Log.e(TAG, "Signing failed", e)
                        if (e is OnchainAlarmService.PendingConfirmationException) {
                            alarmRepository.updateOnchainAddress(alarm.id, e.pda)
                            alarmRepository.queueCreateAlarm(alarm.id)
                            _saveState.value = SaveState.PendingConfirmation(alarm.id)
                        } else {
                            // Reset deposit status since transaction wasn't sent
                            alarmRepository.updateDepositStatus(alarm.id, hasDeposit = false)
                            _saveState.value = SaveState.SigningFailed(alarm.id, e.message ?: "Transaction failed")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Signing exception", e)
                // Reset deposit status since transaction wasn't sent
                alarmRepository.updateDepositStatus(alarm.id, hasDeposit = false)
                _saveState.value = SaveState.SigningFailed(alarm.id, e.message ?: "Transaction failed")
            } finally {
                pendingAlarm = null
                pendingState = null
            }
        }
    }
    
    /**
     * Skip signing and just save alarm without deposit.
     */
    fun skipSigning() {
        val alarm = pendingAlarm ?: return
        viewModelScope.launch {
            // Remove deposit flag from saved alarm
            alarmRepository.updateDepositStatus(alarm.id, hasDeposit = false)
            _saveState.value = SaveState.Success(alarm.id)
            pendingAlarm = null
            pendingState = null
        }
    }
    
    private fun calculateNextTrigger(time: LocalTime): Long {
        val now = LocalDateTime.now()
        var targetDateTime = LocalDateTime.of(LocalDate.now(), time)
        
        if (targetDateTime.isBefore(now)) {
            targetDateTime = targetDateTime.plusDays(1)
        }
        
        return targetDateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun generateOnchainAlarmId(): Long {
        val now = System.currentTimeMillis()
        val rand = (0..1023).random()
        return (now shl 10) or rand.toLong()
    }
    
    fun resetState() {
        _saveState.value = SaveState.Idle
        pendingAlarm = null
        pendingState = null
    }
}

/**
 * Save operation state.
 */
sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class NeedsSigning(val alarmId: Long, val depositAmount: Double) : SaveState()
    object Signing : SaveState()
    data class Success(val alarmId: Long) : SaveState()
    data class PendingConfirmation(val alarmId: Long) : SaveState()
    data class SigningFailed(val alarmId: Long, val message: String) : SaveState()
    data class Error(val message: String) : SaveState()
}
