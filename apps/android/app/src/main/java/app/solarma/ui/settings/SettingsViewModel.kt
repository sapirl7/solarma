package app.solarma.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.solarma.BuildConfig
import app.solarma.alarm.AlarmRepository
import app.solarma.alarm.ImportResult
import app.solarma.wallet.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "solarma_settings")

/**
 * ViewModel for the Settings screen.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val walletManager: WalletManager,
        private val alarmRepository: AlarmRepository,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        companion object {
            val KEY_NFC_TAG_HASH = stringPreferencesKey("nfc_tag_hash")
            val KEY_QR_CODE = stringPreferencesKey("qr_code")
            val KEY_DEFAULT_STEPS = intPreferencesKey("default_steps")
            val KEY_DEFAULT_DEPOSIT = stringPreferencesKey("default_deposit")
            val KEY_DEFAULT_PENALTY = intPreferencesKey("default_penalty")
        }

        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
        val importState: StateFlow<ImportState> = _importState.asStateFlow()

        init {
            // Load saved settings
            viewModelScope.launch {
                context.dataStore.data.collect { prefs ->
                    _uiState.value =
                        _uiState.value.copy(
                            nfcTagRegistered = prefs[KEY_NFC_TAG_HASH] != null,
                            nfcTagHash = prefs[KEY_NFC_TAG_HASH],
                            qrCodeRegistered = prefs[KEY_QR_CODE] != null,
                            qrCode = prefs[KEY_QR_CODE],
                            defaultSteps = prefs[KEY_DEFAULT_STEPS] ?: 50,
                            defaultDepositSol = prefs[KEY_DEFAULT_DEPOSIT]?.toDoubleOrNull() ?: 0.1,
                            defaultPenalty = prefs[KEY_DEFAULT_PENALTY] ?: 0,
                        )
                }
            }

            // Observe wallet connection state
            viewModelScope.launch {
                walletManager.connectionState.collect { state ->
                    _uiState.value =
                        _uiState.value.copy(
                            walletAddress = walletManager.getConnectedWallet(),
                        )
                }
            }
        }

        suspend fun connect(activityResultSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender) {
            walletManager.connect(activityResultSender)
                .onSuccess { pubkey ->
                    _uiState.value =
                        _uiState.value.copy(
                            walletAddress = walletManager.getConnectedWallet(),
                        )
                }
        }

        fun disconnect() {
            walletManager.disconnect()
            _uiState.value = _uiState.value.copy(walletAddress = null)
        }

        fun setVibration(enabled: Boolean) {
            _uiState.value = _uiState.value.copy(vibrationEnabled = enabled)
        }

        fun setDefaultSteps(steps: Int) {
            _uiState.value = _uiState.value.copy(defaultSteps = steps)
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[KEY_DEFAULT_STEPS] = steps
                }
            }
        }

        fun setDefaultDeposit(sol: Double) {
            _uiState.value = _uiState.value.copy(defaultDepositSol = sol)
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[KEY_DEFAULT_DEPOSIT] = sol.toString()
                }
            }
        }

        fun setDefaultPenalty(penalty: Int) {
            _uiState.value = _uiState.value.copy(defaultPenalty = penalty)
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[KEY_DEFAULT_PENALTY] = penalty
                }
            }
        }

        /**
         * Register NFC tag hash for wake proof.
         */
        fun registerNfcTag(tagHash: String) {
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[KEY_NFC_TAG_HASH] = tagHash
                }
                _uiState.value =
                    _uiState.value.copy(
                        nfcTagRegistered = true,
                        nfcTagHash = tagHash,
                    )
            }
        }

        /**
         * Clear registered NFC tag.
         */
        fun clearNfcTag() {
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs.remove(KEY_NFC_TAG_HASH)
                }
                _uiState.value =
                    _uiState.value.copy(
                        nfcTagRegistered = false,
                        nfcTagHash = null,
                    )
            }
        }

        /**
         * Generate new QR code for wake proof.
         */
        fun generateQrCode(): String {
            val code = "SOLARMA-${UUID.randomUUID().toString().take(8).uppercase()}"
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs[KEY_QR_CODE] = code
                }
                _uiState.value =
                    _uiState.value.copy(
                        qrCodeRegistered = true,
                        qrCode = code,
                    )
            }
            return code
        }

        /**
         * Clear registered QR code.
         */
        fun clearQrCode() {
            viewModelScope.launch {
                context.dataStore.edit { prefs ->
                    prefs.remove(KEY_QR_CODE)
                }
                _uiState.value =
                    _uiState.value.copy(
                        qrCodeRegistered = false,
                        qrCode = null,
                    )
            }
        }

        suspend fun importOnchainAlarms() {
            if (_importState.value is ImportState.Loading) return
            val ownerAddress =
                walletManager.getConnectedWallet()
                    ?: run {
                        _importState.value = ImportState.Error("Wallet not connected")
                        return
                    }
            _importState.value = ImportState.Loading
            val result = alarmRepository.importOnchainAlarms(ownerAddress)
            _importState.value =
                result.fold(
                    onSuccess = { ImportState.Success(it) },
                    onFailure = { ImportState.Error(it.message ?: "Import failed") },
                )
        }

        fun resetImportState() {
            _importState.value = ImportState.Idle
        }
    }

/**
 * UI state for Settings screen.
 */
data class SettingsUiState(
    // Wallet
    val walletAddress: String? = null,
    // Fixed at build time (SOLARMA_NETWORK); there is no runtime network switch.
    val isDevnet: Boolean = !BuildConfig.IS_MAINNET,
    // Alarm Defaults
    val defaultSteps: Int = 50,
    val defaultDepositSol: Double = 0.1,
    // 0=burn, 1=donate, 2=buddy
    val defaultPenalty: Int = 0,
    // Wake Proof
    val nfcTagRegistered: Boolean = false,
    val nfcTagHash: String? = null,
    val qrCodeRegistered: Boolean = false,
    val qrCode: String? = null,
    // Sound
    val soundName: String = "Sunrise Glow",
    val vibrationEnabled: Boolean = true,
)

sealed class ImportState {
    object Idle : ImportState()

    object Loading : ImportState()

    data class Success(val result: ImportResult) : ImportState()

    data class Error(val message: String) : ImportState()
}
