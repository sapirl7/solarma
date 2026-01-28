package app.solarma.wallet

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Solana RPC client for transaction submission and queries.
 */
@Singleton
class SolanaRpcClient @Inject constructor() {
    
    companion object {
        private const val TAG = "Solarma.RpcClient"
        
        // Default to devnet, configurable via BuildConfig
        const val DEVNET_RPC = "https://api.devnet.solana.com"
        const val MAINNET_RPC = "https://api.mainnet-beta.solana.com"
    }
    
    private var rpcUrl: String = DEVNET_RPC
    
    fun setNetwork(isMainnet: Boolean) {
        rpcUrl = if (isMainnet) MAINNET_RPC else DEVNET_RPC
        Log.i(TAG, "RPC URL set to: $rpcUrl")
    }
    
    /**
     * Get account balance in lamports.
     */
    suspend fun getBalance(pubkey: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCall(
                method = "getBalance",
                params = """["$pubkey"]"""
            )
            // Parse response - simplified, production should use proper JSON parsing
            val value = response.substringAfter("\"value\":").substringBefore(",").toLongOrNull()
            if (value != null) {
                Result.success(value)
            } else {
                Result.failure(Exception("Failed to parse balance"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBalance failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get latest blockhash for transaction.
     */
    suspend fun getLatestBlockhash(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCall(
                method = "getLatestBlockhash",
                params = ""
            )
            // Parse blockhash from response
            val blockhash = response
                .substringAfter("\"blockhash\":\"")
                .substringBefore("\"")
            if (blockhash.isNotEmpty() && blockhash.length == 44) {
                Result.success(blockhash)
            } else {
                Result.failure(Exception("Invalid blockhash"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLatestBlockhash failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get account info.
     */
    suspend fun getAccountInfo(pubkey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCall(
                method = "getAccountInfo",
                params = """["$pubkey", {"encoding": "base64"}]"""
            )
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "getAccountInfo failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Confirm transaction signature.
     */
    suspend fun confirmTransaction(signature: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCall(
                method = "getSignatureStatuses",
                params = """[["$signature"]]"""
            )
            val confirmed = response.contains("\"confirmationStatus\":\"confirmed\"") ||
                           response.contains("\"confirmationStatus\":\"finalized\"")
            Result.success(confirmed)
        } catch (e: Exception) {
            Log.e(TAG, "confirmTransaction failed", e)
            Result.failure(e)
        }
    }
    
    private fun makeRpcCall(method: String, params: String): String {
        val url = URL(rpcUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
            connection.outputStream.write(body.toByteArray())
            
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw Exception("RPC error ${connection.responseCode}: $error")
            }
        } finally {
            connection.disconnect()
        }
    }
}
