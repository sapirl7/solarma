package app.solarma.wallet

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Solana RPC client for transaction submission and queries.
 * Uses multiple RPC endpoints with automatic fallback on failure.
 */
@Singleton
class SolanaRpcClient @Inject constructor() {
    
    companion object {
        private const val TAG = "Solarma.RpcClient"
        
        // Devnet RPC endpoints (fallback order)
        private val DEVNET_ENDPOINTS = listOf(
            "https://api.devnet.solana.com",
            "https://devnet.helius-rpc.com",
            "https://rpc-devnet.solflare.com",
            "https://devnet.genesysgo.net"
        )
        
        // Mainnet RPC endpoints (fallback order)
        private val MAINNET_ENDPOINTS = listOf(
            "https://api.mainnet-beta.solana.com",
            "https://solana-mainnet.g.alchemy.com/v2/demo",
            "https://rpc.helius.xyz"
        )
    }
    
    private var isMainnet: Boolean = false
    
    private val currentEndpoints: List<String>
        get() = if (isMainnet) MAINNET_ENDPOINTS else DEVNET_ENDPOINTS
    
    fun setNetwork(mainnet: Boolean) {
        isMainnet = mainnet
        Log.i(TAG, "Network set to: ${if (mainnet) "Mainnet" else "Devnet"}")
    }
    
    /**
     * Get account balance in lamports.
     */
    suspend fun getBalance(pubkey: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCallWithFallback(
                method = "getBalance",
                params = """["$pubkey"]"""
            )
            val result = parseResultObject(response)
            val value = result.getLong("value")
            Result.success(value)
        } catch (e: Exception) {
            Log.e(TAG, "getBalance failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get latest blockhash for transaction.
     * Throws on error for use in TransactionBuilder.
     */
    suspend fun getLatestBlockhash(): String = withContext(Dispatchers.IO) {
        val response = makeRpcCallWithFallback(
            method = "getLatestBlockhash",
            params = """[{"commitment": "confirmed"}]"""
        )
        val result = parseResultObject(response)
        val blockhash = result.getJSONObject("value").getString("blockhash")
        Log.d(TAG, "Parsed blockhash: $blockhash")
        
        if (blockhash.isNotEmpty() && blockhash.length >= 32 && blockhash.length <= 44) {
            blockhash
        } else {
            Log.e(TAG, "Invalid blockhash format. Full response: $response")
            throw Exception("Invalid blockhash response: $blockhash")
        }
    }
    
    /**
     * Get account info.
     */
    suspend fun getAccountInfo(pubkey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCallWithFallback(
                method = "getAccountInfo",
                params = """["$pubkey", {"encoding": "base64"}]"""
            )
            // Validate JSON but return raw response for flexibility
            parseResultObject(response)
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
            val status = getSignatureStatus(signature).getOrThrow()
            val confirmed = status.err == null &&
                (status.confirmationStatus == "confirmed" || status.confirmationStatus == "finalized")
            Result.success(confirmed)
        } catch (e: Exception) {
            Log.e(TAG, "confirmTransaction failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch signature status including error details.
     */
    suspend fun getSignatureStatus(signature: String): Result<SignatureStatus> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCallWithFallback(
                method = "getSignatureStatuses",
                params = """[["$signature"]]"""
            )
            val result = parseResultObject(response)
            val statuses = result.getJSONArray("value")
            val status = statuses.optJSONObject(0)
            val confirmation = status?.optString("confirmationStatus", null)
            val errValue = status?.opt("err")
            val err = if (errValue != null && errValue != JSONObject.NULL) errValue.toString() else null
            Result.success(SignatureStatus(confirmationStatus = confirmation, err = err))
        } catch (e: Exception) {
            Log.e(TAG, "getSignatureStatus failed", e)
            Result.failure(e)
        }
    }

    /**
     * Check if an account exists on-chain.
     */
    suspend fun accountExists(pubkey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = makeRpcCallWithFallback(
                method = "getAccountInfo",
                params = """["$pubkey", {"encoding": "base64"}]"""
            )
            val result = parseResultObject(response)
            val value = result.opt("value")
            Result.success(value != null && value != JSONObject.NULL)
        } catch (e: Exception) {
            Log.e(TAG, "accountExists failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send signed transaction to network.
     */
    suspend fun sendTransaction(signedTx: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base64Tx = android.util.Base64.encodeToString(signedTx, android.util.Base64.NO_WRAP)
            val response = makeRpcCallWithFallback(
                method = "sendTransaction",
                params = """["$base64Tx", {"encoding": "base64"}]"""
            )
            val result = parseResultValue(response)
            val signature = result as? String ?: ""
            if (signature.isNotEmpty() && signature.length >= 64) {
                Log.i(TAG, "Transaction sent: $signature")
                Result.success(signature)
            } else {
                Result.failure(Exception("Invalid signature in response: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendTransaction failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Make RPC call with automatic fallback to next endpoint on failure.
     */
    private fun makeRpcCallWithFallback(method: String, params: String): String {
        val errors = mutableListOf<String>()
        
        for ((index, endpoint) in currentEndpoints.withIndex()) {
            try {
                Log.d(TAG, "Trying RPC endpoint ${index + 1}/${currentEndpoints.size}: $endpoint")
                val result = makeRpcCall(endpoint, method, params)
                if (index > 0) {
                    Log.i(TAG, "Fallback successful on endpoint: $endpoint")
                }
                return result
            } catch (e: Exception) {
                val errorMsg = "Endpoint $endpoint failed: ${e.message}"
                Log.w(TAG, errorMsg)
                errors.add(errorMsg)
                // Continue to next endpoint
            }
        }
        
        // All endpoints failed
        throw Exception("All RPC endpoints failed:\n${errors.joinToString("\n")}")
    }
    
    private fun makeRpcCall(rpcUrl: String, method: String, params: String): String {
        val url = URL(rpcUrl)
        val connection = url.openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5000  // 5 second timeout
            connection.readTimeout = 10000    // 10 second read timeout
            connection.doOutput = true
            
            val paramsStr = if (params.isEmpty()) "[]" else params
            val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$paramsStr}"""
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

    private fun parseResultObject(response: String): JSONObject {
        val json = JSONObject(response)
        if (json.has("error")) {
            val message = json.getJSONObject("error").optString("message", "RPC error")
            throw Exception(message)
        }
        return json.getJSONObject("result")
    }

    private fun parseResultValue(response: String): Any? {
        val json = JSONObject(response)
        if (json.has("error")) {
            val message = json.getJSONObject("error").optString("message", "RPC error")
            throw Exception(message)
        }
        return json.opt("result")
    }
}

data class SignatureStatus(
    val confirmationStatus: String?,
    val err: String?
)
