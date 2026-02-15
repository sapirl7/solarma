package app.solarma.wallet

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Solana wallet connection using Mobile Wallet Adapter (MWA) 2.0.
 * Designed for Solana Seeker device with native wallet integration.
 * Persists wallet address across app restarts.
 */
@Singleton
class WalletManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "Solarma.WalletManager"

            // Solarma app identity
            private const val APP_IDENTITY_NAME = "Solarma"
            private val APP_IDENTITY_URI = "https://solarma.app".toUri()

            // Icon must be relative URI for MWA (relative to identityUri)
            private val APP_IDENTITY_ICON = "/icon.png".toUri()

            // SharedPreferences
            private const val PREFS_NAME = "solarma_wallet"
            private const val KEY_WALLET_ADDRESS = "wallet_address"
        }

        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        private val _connectionState = MutableStateFlow<WalletConnectionState>(WalletConnectionState.Disconnected)
        val connectionState: StateFlow<WalletConnectionState> = _connectionState.asStateFlow()

        private var publicKey: ByteArray? = null
        private var publicKeyBase58: String? = null

        init {
            // Restore wallet from SharedPreferences on init
            restoreWalletFromPrefs()
        }

        private fun restoreWalletFromPrefs() {
            val savedAddress = prefs.getString(KEY_WALLET_ADDRESS, null)
            if (savedAddress != null) {
                try {
                    publicKeyBase58 = savedAddress
                    publicKey = fromBase58(savedAddress)
                    _connectionState.value =
                        WalletConnectionState.Connected(
                            publicKey = publicKey!!,
                            publicKeyBase58 = savedAddress,
                            walletName = "Solana Wallet",
                        )
                    Log.i(TAG, "Restored wallet from prefs: $savedAddress")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore wallet from prefs", e)
                    clearWalletPrefs()
                }
            }
        }

        private fun saveWalletToPrefs(address: String) {
            prefs.edit { putString(KEY_WALLET_ADDRESS, address) }
            Log.d(TAG, "Wallet address saved to prefs")
        }

        private fun clearWalletPrefs() {
            prefs.edit { remove(KEY_WALLET_ADDRESS) }
            Log.d(TAG, "Wallet prefs cleared")
        }

        // MWA adapter instance
        private val mobileWalletAdapter by lazy {
            MobileWalletAdapter(
                connectionIdentity =
                    ConnectionIdentity(
                        identityUri = APP_IDENTITY_URI,
                        iconUri = APP_IDENTITY_ICON,
                        identityName = APP_IDENTITY_NAME,
                    ),
            ).apply {
                blockchain = Solana.Devnet
            }
        }

        /**
         * Connect to wallet using MWA on Seeker device.
         * This will open the native wallet for authorization.
         */
        suspend fun connect(activityResultSender: ActivityResultSender): Result<ByteArray> {
            return try {
                _connectionState.value = WalletConnectionState.Connecting
                Log.i(TAG, "Starting MWA connection...")

                when (val result = mobileWalletAdapter.connect(activityResultSender)) {
                    is TransactionResult.Success -> {
                        val authResult = result.authResult
                        if (authResult != null && authResult.accounts.isNotEmpty()) {
                            val account = authResult.accounts.first()
                            val pubKeyBytes = account.publicKey
                            val pubKeyString = toBase58(pubKeyBytes)

                            publicKey = pubKeyBytes
                            publicKeyBase58 = pubKeyString

                            _connectionState.value =
                                WalletConnectionState.Connected(
                                    publicKey = pubKeyBytes,
                                    publicKeyBase58 = pubKeyString,
                                    walletName = authResult.walletUriBase?.host ?: "Solana Wallet",
                                )
                            saveWalletToPrefs(pubKeyString)
                            Log.i(TAG, "Wallet connected: $publicKeyBase58")
                            Result.success(pubKeyBytes)
                        } else {
                            Log.w(TAG, "No accounts returned from wallet")
                            _connectionState.value = WalletConnectionState.Error("No accounts returned")
                            Result.failure(Exception("No accounts returned from wallet"))
                        }
                    }
                    is TransactionResult.Failure -> {
                        Log.e(TAG, "Wallet connection failed: ${result.message}")
                        _connectionState.value = WalletConnectionState.Error(result.message)
                        Result.failure(result.e ?: Exception(result.message))
                    }
                    is TransactionResult.NoWalletFound -> {
                        Log.e(TAG, "No wallet found: ${result.message}")
                        _connectionState.value = WalletConnectionState.Error("No wallet app found")
                        Result.failure(Exception("No MWA-compatible wallet found"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MWA connect exception", e)
                _connectionState.value = WalletConnectionState.Error(e.message ?: "Connection failed")
                Result.failure(e)
            }
        }

        /**
         * Sign and send a transaction using MWA.
         * Returns the transaction signature as Base58.
         */
        suspend fun signAndSendTransaction(
            activityResultSender: ActivityResultSender,
            transaction: ByteArray,
        ): Result<String> {
            if (!isConnected()) {
                return Result.failure(Exception("Wallet not connected"))
            }

            Log.i(TAG, "Signing and sending transaction...")

            return when (
                val result =
                    mobileWalletAdapter.transact(activityResultSender) { authResult ->
                        val response = signAndSendTransactions(arrayOf(transaction))
                        response.signatures.firstOrNull()
                    }
            ) {
                is TransactionResult.Success -> {
                    val sig = result.payload
                    if (sig != null) {
                        val sigBase58 = toBase58(sig)
                        Log.i(TAG, "Transaction sent. Signature: $sigBase58")
                        Result.success(sigBase58)
                    } else {
                        Result.failure(Exception("No signature returned"))
                    }
                }
                is TransactionResult.Failure -> {
                    Log.e(TAG, "Transaction failed: ${result.message}")
                    Result.failure(result.e ?: Exception(result.message))
                }
                is TransactionResult.NoWalletFound -> {
                    Result.failure(Exception("No wallet found"))
                }
            }
        }

        /**
         * Sign transaction without sending (for inspection or custom RPC).
         */
        suspend fun signTransaction(
            activityResultSender: ActivityResultSender,
            transaction: ByteArray,
        ): Result<ByteArray> {
            if (!isConnected()) {
                return Result.failure(Exception("Wallet not connected"))
            }

            Log.i(TAG, "Signing transaction...")

            return when (
                val result =
                    mobileWalletAdapter.transact(activityResultSender) { authResult ->
                        val response = signTransactions(arrayOf(transaction))
                        response.signedPayloads.firstOrNull()
                    }
            ) {
                is TransactionResult.Success -> {
                    val signedTx = result.payload
                    if (signedTx != null) {
                        Log.i(TAG, "Transaction signed successfully")
                        Result.success(signedTx)
                    } else {
                        Result.failure(Exception("No signed transaction returned"))
                    }
                }
                is TransactionResult.Failure -> {
                    Log.e(TAG, "Signing failed: ${result.message}")
                    Result.failure(result.e ?: Exception(result.message))
                }
                is TransactionResult.NoWalletFound -> {
                    Result.failure(Exception("No wallet found"))
                }
            }
        }

        /**
         * Disconnect wallet and clear stored credentials.
         */
        fun disconnect() {
            mobileWalletAdapter.authToken = null
            publicKey = null
            publicKeyBase58 = null
            clearWalletPrefs()
            _connectionState.value = WalletConnectionState.Disconnected
            Log.i(TAG, "Wallet disconnected")
        }

        /**
         * Update Solana network for MWA.
         */
        fun setNetwork(isDevnet: Boolean) {
            mobileWalletAdapter.blockchain = if (isDevnet) Solana.Devnet else Solana.Mainnet
            Log.i(TAG, "MWA network set to: ${if (isDevnet) "Devnet" else "Mainnet"}")
        }

        /**
         * Check if wallet is connected.
         */
        fun isConnected(): Boolean = connectionState.value is WalletConnectionState.Connected

        /**
         * Get connected public key bytes.
         */
        fun getPublicKey(): ByteArray? = publicKey

        /**
         * Get connected public key as Base58 string.
         */
        fun getConnectedWallet(): String? = publicKeyBase58

        /**
         * Encode bytes to Base58.
         */
        private fun toBase58(bytes: ByteArray): String {
            val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            var num = java.math.BigInteger(1, bytes)
            val sb = StringBuilder()
            while (num > java.math.BigInteger.ZERO) {
                val (q, r) = num.divideAndRemainder(java.math.BigInteger.valueOf(58))
                sb.insert(0, alphabet[r.toInt()])
                num = q
            }
            // Handle leading zeros
            for (b in bytes) {
                if (b.toInt() == 0) sb.insert(0, '1') else break
            }
            return sb.toString()
        }

        /**
         * Decode Base58 string to bytes.
         */
        private fun fromBase58(input: String): ByteArray {
            val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            var num = java.math.BigInteger.ZERO
            for (char in input) {
                val index = alphabet.indexOf(char)
                if (index < 0) throw IllegalArgumentException("Invalid Base58 character: $char")
                num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(index.toLong()))
            }

            // Convert to bytes
            var bytes = num.toByteArray()
            // Remove leading zero if present (from BigInteger sign bit)
            if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }

            // Count leading '1's in input (they represent leading zeros)
            val leadingZeros = input.takeWhile { it == '1' }.length

            // Prepend leading zeros
            return ByteArray(leadingZeros) + bytes
        }
    }

/**
 * Wallet connection state.
 */
sealed class WalletConnectionState {
    data object Disconnected : WalletConnectionState()

    data object Connecting : WalletConnectionState()

    data class Connected(
        val publicKey: ByteArray,
        val publicKeyBase58: String,
        val walletName: String,
    ) : WalletConnectionState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Connected) return false
            return publicKey.contentEquals(other.publicKey) &&
                publicKeyBase58 == other.publicKeyBase58 &&
                walletName == other.walletName
        }

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    data class Error(val message: String) : WalletConnectionState()
}
