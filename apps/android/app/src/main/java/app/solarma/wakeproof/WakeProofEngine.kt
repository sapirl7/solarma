package app.solarma.wakeproof

import android.util.Log
import app.solarma.data.local.AlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Engine that enforces wake proof completion.
 * Dispatches to appropriate sensor handler based on alarm config.
 */
class WakeProofEngine @Inject constructor(
    private val stepCounter: StepCounter,
    private val nfcScanner: NfcScanner
) {
    companion object {
        private const val TAG = "Solarma.WakeProofEngine"
        
        // Wake proof types (match AlarmEntity.wakeProofType)
        const val TYPE_NONE = 0
        const val TYPE_STEPS = 1
        const val TYPE_NFC = 2
        const val TYPE_QR = 3
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _progress = MutableStateFlow(WakeProgress())
    val progress: StateFlow<WakeProgress> = _progress.asStateFlow()
    
    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()
    
    private var currentAlarm: AlarmEntity? = null
    
    /**
     * Start wake proof challenge for the given alarm.
     */
    fun start(alarm: AlarmEntity) {
        currentAlarm = alarm
        _isComplete.value = false
        
        Log.i(TAG, "Starting wake proof: type=${alarm.wakeProofType}, target=${alarm.targetSteps}")
        
        when (alarm.wakeProofType) {
            TYPE_NONE -> {
                // No proof required - but still require explicit action
                _progress.value = WakeProgress(
                    type = TYPE_NONE,
                    message = "Tap to confirm you're awake",
                    requiresAction = true
                )
            }
            
            TYPE_STEPS -> {
                startStepChallenge(alarm.targetSteps)
            }
            
            TYPE_NFC -> {
                startNfcChallenge(alarm.tagHash)
            }
            
            TYPE_QR -> {
                // Fallback to steps if QR not implemented
                _progress.value = WakeProgress(
                    type = TYPE_QR,
                    message = "QR not available, using steps instead",
                    fallbackActive = true
                )
                startStepChallenge(alarm.targetSteps)
            }
            
            else -> {
                Log.w(TAG, "Unknown wake proof type: ${alarm.wakeProofType}")
                _progress.value = WakeProgress(
                    type = TYPE_NONE,
                    message = "Unknown challenge type",
                    requiresAction = true
                )
            }
        }
    }
    
    /**
     * Start step counting challenge.
     */
    private fun startStepChallenge(targetSteps: Int) {
        if (!stepCounter.isAvailable()) {
            _progress.value = WakeProgress(
                type = TYPE_STEPS,
                message = "Step sensor unavailable. Shake device instead.",
                fallbackActive = true
            )
            // TODO: Implement shake fallback
            return
        }
        
        _progress.value = WakeProgress(
            type = TYPE_STEPS,
            currentValue = 0,
            targetValue = targetSteps,
            message = "Walk $targetSteps steps"
        )
        
        scope.launch {
            stepCounter.countSteps(targetSteps).collect { stepProgress ->
                _progress.value = WakeProgress(
                    type = TYPE_STEPS,
                    currentValue = stepProgress.currentSteps,
                    targetValue = stepProgress.targetSteps,
                    progressPercent = stepProgress.progress,
                    message = "${stepProgress.currentSteps} / ${stepProgress.targetSteps} steps"
                )
                
                if (stepProgress.isComplete) {
                    complete()
                }
            }
        }
    }
    
    /**
     * Start NFC tag scanning challenge.
     */
    private fun startNfcChallenge(tagHash: String?) {
        if (tagHash == null) {
            _progress.value = WakeProgress(
                type = TYPE_NFC,
                message = "No NFC tag registered. Go to Settings to register.",
                error = "No tag registered"
            )
            return
        }
        
        _progress.value = WakeProgress(
            type = TYPE_NFC,
            message = "Scan your registered NFC tag"
        )
        
        // Start listening for NFC
        nfcScanner.startScanning(tagHash.toByteArray())
        
        scope.launch {
            nfcScanner.scanResult.collect { result ->
                when (result) {
                    is NfcScanResult.Success -> {
                        _progress.value = WakeProgress(
                            type = TYPE_NFC,
                            message = "Tag verified!",
                            progressPercent = 1f
                        )
                        complete()
                    }
                    is NfcScanResult.WrongTag -> {
                        _progress.value = _progress.value.copy(
                            message = "Wrong tag! Scan the registered tag.",
                            error = "Wrong tag"
                        )
                    }
                    is NfcScanResult.Error -> {
                        _progress.value = _progress.value.copy(
                            message = result.message,
                            error = result.message
                        )
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * For TYPE_NONE: manual confirmation.
     */
    fun confirmAwake() {
        if (currentAlarm?.wakeProofType == TYPE_NONE) {
            complete()
        }
    }
    
    /**
     * Mark challenge as complete.
     */
    private fun complete() {
        Log.i(TAG, "Wake proof completed!")
        _isComplete.value = true
        _progress.value = _progress.value.copy(
            message = "Challenge complete! ☀️",
            progressPercent = 1f
        )
        
        // Cleanup
        nfcScanner.stopScanning()
    }
    
    /**
     * Stop and cleanup.
     */
    fun stop() {
        nfcScanner.stopScanning()
        currentAlarm = null
    }
}

/**
 * Progress state for wake proof UI.
 */
data class WakeProgress(
    val type: Int = WakeProofEngine.TYPE_NONE,
    val currentValue: Int = 0,
    val targetValue: Int = 0,
    val progressPercent: Float = 0f,
    val message: String = "",
    val error: String? = null,
    val requiresAction: Boolean = false,
    val fallbackActive: Boolean = false
)
