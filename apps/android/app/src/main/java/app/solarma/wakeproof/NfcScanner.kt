package app.solarma.wakeproof

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NFC tag scanner for Wake Proof challenge.
 * Validates scanned tag against registered tag hash.
 */
@Singleton
class NfcScanner @Inject constructor() {

    companion object {
        private const val TAG = "Solarma.NfcScanner"
    }

    private val _scanResult = MutableStateFlow<NfcScanResult>(NfcScanResult.Idle)
    val scanResult: StateFlow<NfcScanResult> = _scanResult.asStateFlow()

    private var expectedTagHash: ByteArray? = null

    /**
     * Check if NFC is available on the device.
     */
    fun isAvailable(activity: Activity): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        return adapter != null && adapter.isEnabled
    }

    /**
     * Start waiting for NFC tag scan.
     * @param tagHash Expected tag hash to validate against
     */
    fun startScanning(tagHash: ByteArray) {
        expectedTagHash = tagHash
        _scanResult.value = NfcScanResult.Waiting
        Log.d(TAG, "Started NFC scanning")
    }

    /**
     * Handle scanned NFC tag.
     * Called from Activity's onNewIntent.
     */
    fun handleTag(tag: Tag) {
        val tagId = tag.id
        val scannedHash = hashTagId(tagId)

        Log.d(TAG, "Tag scanned: ${tagId.toHexString()}")

        val expected = expectedTagHash
        if (expected == null) {
            _scanResult.value = NfcScanResult.Error("Not scanning")
            return
        }

        if (scannedHash.contentEquals(expected)) {
            _scanResult.value = NfcScanResult.Success
            Log.i(TAG, "NFC tag validated!")
        } else {
            _scanResult.value = NfcScanResult.WrongTag
            Log.w(TAG, "Wrong tag scanned")
        }
    }

    /**
     * Hash tag ID for storage (privacy).
     */
    fun hashTagId(tagId: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(tagId)
    }

    fun stopScanning() {
        expectedTagHash = null
        _scanResult.value = NfcScanResult.Idle
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of NFC scan.
 */
sealed class NfcScanResult {
    object Idle : NfcScanResult()
    object Waiting : NfcScanResult()
    object Success : NfcScanResult()
    object WrongTag : NfcScanResult()
    data class Error(val message: String) : NfcScanResult()
}
