package app.solarma.wallet

import android.app.Activity
import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Solana wallet connection using Mobile Wallet Adapter (MWA).
 * Supports Phantom, Solflare, and other MWA-compatible wallets.
 */
@Singleton
class WalletManager @Inject constructor() {
    
    companion object {
        private const val TAG = "Solarma.WalletManager"
        
        // Solarma app identity for wallet auth
        private const val APP_NAME = "Solarma"
        private val APP_ICON_URI = Uri.parse("https://solarma.app/icon.png")
        private val APP_IDENTITY_URI = Uri.parse("https://solarma.app")
    }
    
    private val _connectionState = MutableStateFlow<WalletConnectionState>(WalletConnectionState.Disconnected)
    val connectionState: StateFlow<WalletConnectionState> = _connectionState.asStateFlow()
    
    private var authToken: String? = null
    private var publicKey: ByteArray? = null
    
    /**
     * Connect to wallet using MWA.
     */
    suspend fun connect(
        activity: Activity,
        activityResultSender: ActivityResultSender
    ): Result<ByteArray> {
        return try {
            _connectionState.value = WalletConnectionState.Connecting
            
            val adapter = MobileWalletAdapter()
            
            val result = adapter.transact(activityResultSender) { 
                authorize(
                    identityUri = APP_IDENTITY_URI,
                    iconUri = APP_ICON_URI,
                    identityName = APP_NAME,
                    cluster = "devnet" // TODO: Make configurable
                )
            }
            
            // Store auth token for future transactions
            authToken = result.authToken
            publicKey = result.accounts.firstOrNull()?.publicKey
            
            if (publicKey != null) {
                _connectionState.value = WalletConnectionState.Connected(
                    publicKey = publicKey!!,
                    walletName = result.walletUriBase?.host ?: "Unknown Wallet"
                )
                Log.i(TAG, "Wallet connected: ${publicKey?.toBase58()}")
                Result.success(publicKey!!)
            } else {
                _connectionState.value = WalletConnectionState.Error("No accounts returned")
                Result.failure(Exception("No accounts returned from wallet"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wallet connection failed", e)
            _connectionState.value = WalletConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    /**
     * Sign and send a transaction.
     */
    suspend fun signAndSendTransaction(
        activityResultSender: ActivityResultSender,
        transaction: ByteArray
    ): Result<String> {
        val token = authToken ?: return Result.failure(Exception("Not connected"))
        
        return try {
            val adapter = MobileWalletAdapter()
            
            val result = adapter.transact(activityResultSender) {
                reauthorize(
                    identityUri = APP_IDENTITY_URI,
                    iconUri = APP_ICON_URI,
                    identityName = APP_NAME,
                    authToken = token
                )
                
                signAndSendTransactions(arrayOf(transaction))
            }
            
            val signature = result.signatures.firstOrNull()
            if (signature != null) {
                val sigBase58 = signature.toBase58()
                Log.i(TAG, "Transaction sent: $sigBase58")
                Result.success(sigBase58)
            } else {
                Result.failure(Exception("No signature returned"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Disconnect wallet.
     */
    fun disconnect() {
        authToken = null
        publicKey = null
        _connectionState.value = WalletConnectionState.Disconnected
        Log.i(TAG, "Wallet disconnected")
    }
    
    /**
     * Check if wallet is connected.
     */
    fun isConnected(): Boolean = connectionState.value is WalletConnectionState.Connected
    
    /**
     * Get connected public key.
     */
    fun getPublicKey(): ByteArray? = publicKey
    
    // Extension function for Base58 encoding
    private fun ByteArray.toBase58(): String {
        // Simplified Base58 for display - in production use a proper library
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger(1, this)
        val sb = StringBuilder()
        while (num > java.math.BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(java.math.BigInteger.valueOf(58))
            sb.insert(0, alphabet[r.toInt()])
            num = q
        }
        // Handle leading zeros
        for (b in this) {
            if (b.toInt() == 0) sb.insert(0, '1') else break
        }
        return sb.toString()
    }
}

/**
 * Wallet connection state.
 */
sealed class WalletConnectionState {
    object Disconnected : WalletConnectionState()
    object Connecting : WalletConnectionState()
    data class Connected(
        val publicKey: ByteArray,
        val walletName: String
    ) : WalletConnectionState()
    data class Error(val message: String) : WalletConnectionState()
}
