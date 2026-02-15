package app.solarma

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.solarma.navigation.SolarmaNavHost
import app.solarma.ui.theme.SolarmaTheme
import app.solarma.ui.settings.SettingsViewModel
import app.solarma.ui.settings.dataStore
import app.solarma.wakeproof.NfcScanner
import app.solarma.wallet.SolanaRpcClient
import app.solarma.wallet.OnchainAlarmService
import app.solarma.wallet.TransactionProcessor
import app.solarma.wallet.WalletManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * CompositionLocal to provide ActivityResultSender to composables.
 */
val LocalActivityResultSender = staticCompositionLocalOf<ActivityResultSender> {
    error("ActivityResultSender not provided")
}

/**
 * Callback for NFC tag detection.
 */
interface NfcTagCallback {
    fun onTagDetected(tagHash: String)
}

/**
 * CompositionLocal for NFC tag callback.
 */
val LocalNfcTagCallback = staticCompositionLocalOf<NfcTagCallback?> { null }

/**
 * Main entry activity for Solarma.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "Solarma.MainActivity"
    }
    
    @Inject
    lateinit var nfcScanner: NfcScanner

    @Inject
    lateinit var transactionProcessor: TransactionProcessor

    @Inject
    lateinit var walletManager: WalletManager

    @Inject
    lateinit var rpcClient: SolanaRpcClient
    
    @Inject
    lateinit var onchainAlarmService: OnchainAlarmService
    
    @Inject
    lateinit var alarmDao: app.solarma.data.local.AlarmDao
    
    // Create ActivityResultSender BEFORE onCreate completes
    private val activityResultSender by lazy {
        ActivityResultSender(this)
    }
    
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var nfcTagCallback: NfcTagCallback? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "POST_NOTIFICATIONS permission granted")
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS permission denied")
        }
    }

    private val activityRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "ACTIVITY_RECOGNITION permission granted")
        } else {
            Log.w(TAG, "ACTIVITY_RECOGNITION permission denied — step counter may not work")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        
        // Initialize sender before setContent
        val sender = activityResultSender
        
        // NFC callback for Settings registration
        val nfcCallback = object : NfcTagCallback {
            override fun onTagDetected(tagHash: String) {
                nfcTagCallback?.onTagDetected(tagHash)
            }
        }
        
        setContent {
            SolarmaTheme {
                CompositionLocalProvider(
                    LocalActivityResultSender provides sender,
                    LocalNfcTagCallback provides nfcCallback
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SolarmaNavHost()
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Request step counter permission at startup (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        lifecycleScope.launch {
            val prefs = dataStore.data.first()
            val isDevnet = prefs[SettingsViewModel.KEY_IS_DEVNET] ?: true
            walletManager.setNetwork(isDevnet)
            rpcClient.setNetwork(!isDevnet)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Enable foreground dispatch for NFC
        nfcAdapter?.enableForegroundDispatch(
            this,
            pendingIntent,
            arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)),
            null,
        )

        lifecycleScope.launch {
            transactionProcessor.processPendingTransactionsWithUi(activityResultSender)
        }
        // M3: Sync on-chain alarms from other devices
        lifecycleScope.launch {
            try {
                val synced = onchainAlarmService.syncOnchainAlarms(alarmDao)
                if (synced > 0) {
                    android.util.Log.i(TAG, "Synced $synced on-chain alarms from other devices")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "On-chain sync failed (non-blocking)", e)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Disable foreground dispatch
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            
            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, android.nfc.Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            tag?.let {
                Log.i(TAG, "NFC tag detected: ${it.id.toHexString()}")
                
                // Hash the tag ID for privacy
                val tagHash = hashTagId(it.id)
                
                // Notify callback (for Settings registration)
                nfcTagCallback?.onTagDetected(tagHash)
                
                // Also pass to NfcScanner for wake proof validation
                nfcScanner.handleTag(it)
            }
        }
    }
    
    fun setNfcTagCallback(callback: NfcTagCallback?) {
        nfcTagCallback = callback
    }
    
    private fun hashTagId(tagId: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(tagId)
        return hash.toHexString()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
