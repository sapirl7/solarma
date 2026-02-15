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
    private val nfcScanner: NfcScanner,
    private val qrScanner: QrScanner
) {
    companion object {
        private const val TAG = "Solarma.WakeProofEngine"

        // Wake proof types (match AlarmEntity.wakeProofType)
        const val TYPE_NONE = 0
        const val TYPE_STEPS = 1
        const val TYPE_NFC = 2
        const val TYPE_QR = 3
    }

    private var scope: CoroutineScope? = null
    private var stepCollectionJob: kotlinx.coroutines.Job? = null

    private val _progress = MutableStateFlow(WakeProgress())
    val progress: StateFlow<WakeProgress> = _progress.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    private var currentAlarm: AlarmEntity? = null

    /**
     * Start wake proof challenge for the given alarm.
     * @param alarm the alarm entity to prove wake for
     * @param lifecycleScope scope tied to the caller's lifecycle (e.g. Activity.lifecycleScope).
     *        All coroutines will be cancelled when this scope is cancelled.
     */
    fun start(alarm: AlarmEntity, lifecycleScope: CoroutineScope) {
        scope = lifecycleScope
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
                startQrChallenge(alarm.qrCode)
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
     * Falls back to manual confirm after 60 seconds if no progress.
     */
    private fun startStepChallenge(targetSteps: Int) {
        val hasDeposit = currentAlarm?.hasDeposit == true

        if (!stepCounter.isAvailable()) {
            if (hasDeposit) {
                // SECURITY: Deposit alarms must NOT get a free bypass
                _progress.value = WakeProgress(
                    type = TYPE_STEPS,
                    message = "Step sensor unavailable. Claim your deposit from the app.",
                    fallbackActive = false,
                    requiresAction = false
                )
            } else {
                _progress.value = WakeProgress(
                    type = TYPE_STEPS,
                    message = "Step sensor unavailable. Tap to confirm you're awake.",
                    fallbackActive = true,
                    requiresAction = true
                )
            }
            return
        }

        _progress.value = WakeProgress(
            type = TYPE_STEPS,
            currentValue = 0,
            targetValue = targetSteps,
            message = "Walk $targetSteps steps"
        )

        // Track start time for timeout
        val startTime = System.currentTimeMillis()
        var lastStepCount = 0

        stepCollectionJob = scope?.launch {
            stepCounter.countSteps(targetSteps).collect { stepProgress ->
                lastStepCount = stepProgress.currentSteps

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

                // Check for timeout (60 seconds with no progress)
                // SECURITY: No fallback for deposit alarms
                if (stepProgress.currentSteps == 0 &&
                    System.currentTimeMillis() - startTime > 60_000 &&
                    !hasDeposit) {
                    Log.w(TAG, "Step challenge timeout, enabling manual fallback")
                    _progress.value = WakeProgress(
                        type = TYPE_STEPS,
                        message = "Sensor not responding. Tap to confirm you're awake.",
                        fallbackActive = true,
                        requiresAction = true
                    )
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

        val expectedHash = decodeHex(tagHash)
        if (expectedHash == null) {
            _progress.value = WakeProgress(
                type = TYPE_NFC,
                message = "Invalid NFC tag data. Re-register your tag.",
                error = "Invalid tag hash"
            )
            return
        }

        _progress.value = WakeProgress(
            type = TYPE_NFC,
            message = "Scan your registered NFC tag"
        )

        // Start listening for NFC
        nfcScanner.startScanning(expectedHash)

        scope?.launch {
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
        if (currentAlarm?.wakeProofType == TYPE_NONE || _progress.value.fallbackActive) {
            complete()
        }
    }

    /**
     * Activate manual fallback for the current challenge.
     */
    fun activateFallback(message: String) {
        val type = currentAlarm?.wakeProofType ?: _progress.value.type
        _progress.value = _progress.value.copy(
            type = type,
            message = message,
            fallbackActive = true,
            requiresAction = true,
            error = null
        )
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

        // Cleanup all sensors and flows
        nfcScanner.stopScanning()
        qrScanner.stopScanning()
        stepCollectionJob?.cancel()
        stepCollectionJob = null
    }

    /**
     * Stop and cleanup.
     */
    fun stop() {
        nfcScanner.stopScanning()
        qrScanner.stopScanning()
        stepCollectionJob?.cancel()
        stepCollectionJob = null
        currentAlarm = null
        scope = null  // M5: Release lifecycle scope reference
    }

    /**
     * Start QR code scanning challenge.
     */
    private fun startQrChallenge(expectedCode: String?) {
        if (expectedCode == null) {
            _progress.value = WakeProgress(
                type = TYPE_QR,
                message = "No QR code registered. Go to Settings to generate one.",
                error = "No code registered"
            )
            return
        }

        _progress.value = WakeProgress(
            type = TYPE_QR,
            message = "Point camera at your registered QR code"
        )

        // Start QR scanning
        qrScanner.startScanning(expectedCode)

        scope?.launch {
            qrScanner.scanResult.collect { result ->
                when (result) {
                    is QrScanResult.Success -> {
                        _progress.value = WakeProgress(
                            type = TYPE_QR,
                            message = "QR code verified! ✅",
                            progressPercent = 1f
                        )
                        complete()
                    }
                    is QrScanResult.WrongCode -> {
                        _progress.value = _progress.value.copy(
                            message = "Wrong QR code! Scan the registered one.",
                            error = "Wrong code"
                        )
                    }
                    is QrScanResult.Error -> {
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

    private fun decodeHex(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                val index = i * 2
                clean.substring(index, index + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
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
