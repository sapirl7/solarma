package app.solarma.wallet

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import org.sol4k.PublicKey

data class OnchainAlarmAccount(
    val pubkey: String,
    val owner: String,
    val alarmTimeUnix: Long,
    val deadlineUnix: Long,
    val initialAmount: Long,
    val remainingAmount: Long,
    val penaltyRoute: Int,
    val penaltyDestination: String?,
    val snoozeCount: Int,
    val status: Int
)

object OnchainAlarmParser {
    private const val TAG = "Solarma.AlarmParser"

    private val ALARM_DISCRIMINATOR = discriminator("account:Alarm")

    private fun discriminator(name: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(name.toByteArray()).copyOfRange(0, 8)
    }

    fun parse(pubkey: String, dataBase64: String): OnchainAlarmAccount? {
        return try {
            val data = Base64.getDecoder().decode(dataBase64)
            if (data.size < 80) {
                Log.w(TAG, "Account too small for Alarm: ${data.size} bytes")
                return null
            }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val disc = ByteArray(8)
            buffer.get(disc)
            if (!disc.contentEquals(ALARM_DISCRIMINATOR)) {
                return null
            }

            val ownerBytes = ByteArray(32)
            buffer.get(ownerBytes)
            val owner = PublicKey(ownerBytes).toBase58()

            val alarmId = buffer.long       // alarm_id (u64)
            val alarmTime = buffer.long     // alarm_time (i64)
            val deadline = buffer.long      // deadline (i64)

            val initialAmount = buffer.long   // initial_amount (u64)
            val remainingAmount = buffer.long // remaining_amount (u64)

            val penaltyRoute = buffer.get().toInt() and 0xFF

            val hasPenaltyDest = buffer.get().toInt()
            val penaltyDestination = if (hasPenaltyDest == 1) {
                val destBytes = ByteArray(32)
                buffer.get(destBytes)
                PublicKey(destBytes).toBase58()
            } else {
                null
            }

            val snoozeCount = buffer.get().toInt() and 0xFF
            val status = buffer.get().toInt() and 0xFF

            // bump + vault_bump
            if (buffer.remaining() >= 2) {
                buffer.get()
                buffer.get()
            }

            OnchainAlarmAccount(
                pubkey = pubkey,
                owner = owner,
                alarmTimeUnix = alarmTime,
                deadlineUnix = deadline,
                initialAmount = initialAmount,
                remainingAmount = remainingAmount,
                penaltyRoute = penaltyRoute,
                penaltyDestination = penaltyDestination,
                snoozeCount = snoozeCount,
                status = status
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse alarm account $pubkey", e)
            null
        }
    }
}
