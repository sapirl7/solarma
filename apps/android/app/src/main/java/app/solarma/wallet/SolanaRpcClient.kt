package app.solarma.wallet

import android.util.Log
import app.solarma.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        
        // Default Devnet RPC endpoints (fallback order)
        private val DEFAULT_DEVNET_ENDPOINTS = listOf(
            "https://api.devnet.solana.com",
            "https://devnet.helius-rpc.com",
            "https://rpc-devnet.solflare.com",
            "https://devnet.genesysgo.net"
        )
        
        // Default Mainnet RPC endpoints (fallback order)
        private val DEFAULT_MAINNET_ENDPOINTS = listOf(
            "https://api.mainnet-beta.solana.com",
            "https://solana-mainnet.g.alchemy.com/v2/demo",
            "https://rpc.helius.xyz"
        )

        private const val BASE_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
    
    private var isMainnet: Boolean = false
    private val endpointFailureCount = mutableMapOf<String, Int>()
    private val endpointLastFailedAt = mutableMapOf<String, Long>()
    
    private fun parseEndpoints(raw: String): List<String> {
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private val devnetEndpoints: List<String> by lazy {
        parseEndpoints(BuildConfig.SOLANA_RPC_DEVNET).ifEmpty { DEFAULT_DEVNET_ENDPOINTS }
    }

    private val mainnetEndpoints: List<String> by lazy {
        parseEndpoints(BuildConfig.SOLANA_RPC_MAINNET).ifEmpty { DEFAULT_MAINNET_ENDPOINTS }
    }

    private val currentEndpoints: List<String>
        get() = if (isMainnet) mainnetEndpoints else devnetEndpoints
    
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
     * Fetch program accounts filtered by owner (memcmp on Alarm.owner offset).
     */
    suspend fun getProgramAccounts(programId: String, ownerAddress: String): Result<List<ProgramAccount>> =
        withContext(Dispatchers.IO) {
            try {
                val params = """
                    ["$programId", {
                        "encoding": "base64",
                        "filters": [
                            {"memcmp": {"offset": 8, "bytes": "$ownerAddress"}}
                        ]
                    }]
                """.trimIndent()
                val response = makeRpcCallWithFallback(
                    method = "getProgramAccounts",
                    params = params
                )
                val json = JSONObject(response)
                if (json.has("error")) {
                    val message = json.getJSONObject("error").optString("message", "RPC error")
                    throw Exception(message)
                }
                val result = json.getJSONArray("result")
                val accounts = mutableListOf<ProgramAccount>()
                for (i in 0 until result.length()) {
                    val item = result.getJSONObject(i)
                    val pubkey = item.getString("pubkey")
                    val account = item.getJSONObject("account")
                    val dataField = account.get("data")
                    val dataBase64 = when (dataField) {
                        is JSONArray -> dataField.getString(0)
                        else -> dataField.toString()
                    }
                    accounts.add(ProgramAccount(pubkey = pubkey, dataBase64 = dataBase64))
                }
                Result.success(accounts)
            } catch (e: Exception) {
                Log.e(TAG, "getProgramAccounts failed", e)
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
        
        val now = System.currentTimeMillis()
        for ((index, endpoint) in currentEndpoints.withIndex()) {
            try {
                val lastFailed = endpointLastFailedAt[endpoint]
                val failures = endpointFailureCount[endpoint] ?: 0
                val cooldown = (BASE_BACKOFF_MS * (1L shl failures.coerceAtMost(6)))
                    .coerceAtMost(MAX_BACKOFF_MS)
                if (lastFailed != null && now - lastFailed < cooldown) {
                    Log.d(TAG, "Skipping endpoint due to backoff (${cooldown}ms): $endpoint")
                    continue
                }
                Log.d(TAG, "Trying RPC endpoint ${index + 1}/${currentEndpoints.size}: $endpoint")
                val result = makeRpcCall(endpoint, method, params)
                endpointFailureCount.remove(endpoint)
                endpointLastFailedAt.remove(endpoint)
                if (index > 0) {
                    Log.i(TAG, "Fallback successful on endpoint: $endpoint")
                }
                return result
            } catch (e: Exception) {
                val errorMsg = "Endpoint $endpoint failed: ${e.message}"
                Log.w(TAG, errorMsg)
                errors.add(errorMsg)
                val failures = (endpointFailureCount[endpoint] ?: 0) + 1
                endpointFailureCount[endpoint] = failures.coerceAtMost(10)
                endpointLastFailedAt[endpoint] = now
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
            val bodyJson = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                put("params", org.json.JSONTokener(paramsStr).nextValue())
            }
            val body = bodyJson.toString()
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
    
    /**
     * H2: Check clock drift between device and Solana network.
     * Compares device wall clock with the on-chain block time of the latest slot.
     * @return drift in seconds (positive = device ahead, negative = device behind),
     *         or null if RPC call fails.
     */
    suspend fun checkTimeDrift(): Long? = withContext(Dispatchers.IO) {
        try {
            // Get latest slot
            val slotResponse = makeRpcCallWithFallback(
                method = "getSlot",
                params = """[{"commitment": "confirmed"}]"""
            )
            val slot = parseResultValue(slotResponse) as? Number
                ?: return@withContext null
            
            // Get block time for that slot
            val blockTimeResponse = makeRpcCallWithFallback(
                method = "getBlockTime",
                params = "[${slot.toLong()}]"
            )
            val blockTimeUnix = parseResultValue(blockTimeResponse) as? Number
                ?: return@withContext null
            
            val deviceTimeUnix = System.currentTimeMillis() / 1000
            val drift = deviceTimeUnix - blockTimeUnix.toLong()
            
            Log.i(TAG, "Time drift: device=$deviceTimeUnix, chain=${blockTimeUnix.toLong()}, drift=${drift}s")
            
            if (kotlin.math.abs(drift) > 300) { // 5 minutes
                Log.w(TAG, "⚠️ Significant time drift detected: ${drift}s. Alarm deadlines may be missed!")
            }
            
            drift
        } catch (e: Exception) {
            Log.e(TAG, "checkTimeDrift failed", e)
            null
        }
    }
}

data class SignatureStatus(
    val confirmationStatus: String?,
    val err: String?
)

data class ProgramAccount(
    val pubkey: String,
    val dataBase64: String
)
