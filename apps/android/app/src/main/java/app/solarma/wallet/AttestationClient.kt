package app.solarma.wallet

import android.util.Log
import app.solarma.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttestationClient @Inject constructor() {
    companion object {
        private const val TAG = "Solarma.Attestation"
    }

    suspend fun requestAckPermit(
        cluster: String,
        programId: String,
        alarmPda: String,
        owner: String,
        nonce: Long,
        expTs: Long,
        proofType: Int,
        proofHashHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.SOLARMA_ATTESTATION_SERVER_URL.trim()
        if (baseUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Attestation server not configured"))
        }
        val endpoint = baseUrl.removeSuffix("/") + "/v1/permit/ack"

        try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject()
                .put("cluster", cluster)
                .put("program_id", programId)
                .put("alarm_pda", alarmPda)
                .put("owner", owner)
                // Send as string to avoid JSON number precision issues for u64.
                .put("nonce", nonce.toString())
                .put("exp_ts", expTs)
                .put("proof_type", proofType)
                .put("proof_hash", proofHashHex)

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            if (code !in 200..299) {
                Log.w(TAG, "Permit request failed: HTTP $code: $body")
                return@withContext Result.failure(Exception("Attestation server error ($code)"))
            }

            val json = JSONObject(body)
            val signatureBase58 = json.optString("signature", "")
            if (signatureBase58.isBlank()) {
                return@withContext Result.failure(Exception("Missing 'signature' in attestation response"))
            }

            Result.success(signatureBase58)
        } catch (e: Exception) {
            Log.e(TAG, "Permit request failed", e)
            Result.failure(e)
        }
    }
}

