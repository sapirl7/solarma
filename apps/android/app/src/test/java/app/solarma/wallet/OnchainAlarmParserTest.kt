package app.solarma.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

class OnchainAlarmParserTest {

    @Test
    fun parseAlarmWithPenaltyDestination() {
        val owner = "11111111111111111111111111111111"
        val dest = "1nc1nerator11111111111111111111111111111111"
        val base64 = buildAlarmAccountBase64(
            owner = owner,
            alarmTime = 1_700_000_000L,
            deadline = 1_700_000_600L,
            initialAmount = 2_000_000_000L,
            remainingAmount = 1_500_000_000L,
            penaltyRoute = 1,
            penaltyDestination = dest,
            snoozeCount = 2,
            status = 0
        )

        val parsed = OnchainAlarmParser.parse("11111111111111111111111111111111", base64)
        assertNotNull(parsed)
        parsed!!
        assertEquals(owner, parsed.owner)
        assertEquals(1_700_000_000L, parsed.alarmTimeUnix)
        assertEquals(1_700_000_600L, parsed.deadlineUnix)
        assertEquals(2_000_000_000L, parsed.initialAmount)
        assertEquals(1_500_000_000L, parsed.remainingAmount)
        assertEquals(1, parsed.penaltyRoute)
        assertEquals(dest, parsed.penaltyDestination)
        assertEquals(2, parsed.snoozeCount)
        assertEquals(0, parsed.status)
    }

    @Test
    fun parseAlarmWithoutPenaltyDestination() {
        val owner = "11111111111111111111111111111111"
        val base64 = buildAlarmAccountBase64(
            owner = owner,
            alarmTime = 1_700_000_100L,
            deadline = 1_700_000_700L,
            initialAmount = 1_000_000L,
            remainingAmount = 900_000L,
            penaltyRoute = 0,
            penaltyDestination = null,
            snoozeCount = 0,
            status = 0
        )

        val parsed = OnchainAlarmParser.parse("11111111111111111111111111111111", base64)
        assertNotNull(parsed)
        parsed!!
        assertEquals(owner, parsed.owner)
        assertEquals(0, parsed.penaltyRoute)
        assertNull(parsed.penaltyDestination)
    }

    @Test
    fun parseIgnoresNonAlarmAccount() {
        val owner = "11111111111111111111111111111111"
        val base64 = buildAlarmAccountBase64(
            owner = owner,
            alarmTime = 1_700_000_200L,
            deadline = 1_700_000_800L,
            initialAmount = 1_000_000L,
            remainingAmount = 1_000_000L,
            penaltyRoute = 0,
            penaltyDestination = null,
            snoozeCount = 0,
            status = 0
        )
        val corrupted = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(base64).apply { this[0] = (this[0] + 1).toByte() }
        )

        val parsed = OnchainAlarmParser.parse("11111111111111111111111111111111", corrupted)
        assertNull(parsed)
    }

    private fun buildAlarmAccountBase64(
        owner: String,
        alarmTime: Long,
        deadline: Long,
        initialAmount: Long,
        remainingAmount: Long,
        penaltyRoute: Int,
        penaltyDestination: String?,
        snoozeCount: Int,
        status: Int,
        alarmId: Long = 1L
    ): String {
        val discriminator = discriminator("account:Alarm")
        val ownerBytes = PublicKey(owner).bytes()
        val penaltyBytes = penaltyDestination?.let { PublicKey(it).bytes() }

        // Rust Alarm struct layout:
        // 8 disc + 32 owner + 8 alarm_id + 8 alarm_time + 8 deadline +
        // 8 initial + 8 remaining + 1 route +
        // 1 + (32 if Some) penalty_dest + 1 snooze + 1 status + 1 bump + 1 vault_bump + 32 padding
        val size = 8 + 32 + 8 + 8 + 8 +
            8 + 8 +
            1 +
            1 + (penaltyBytes?.size ?: 0) +
            1 + 1 + 1 + 1 +
            32

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(discriminator)
        buffer.put(ownerBytes)
        buffer.putLong(alarmId)             // alarm_id
        buffer.putLong(alarmTime)           // alarm_time
        buffer.putLong(deadline)            // deadline
        buffer.putLong(initialAmount)       // initial_amount
        buffer.putLong(remainingAmount)     // remaining_amount
        buffer.put(penaltyRoute.toByte())   // penalty_route
        if (penaltyBytes != null) {
            buffer.put(1.toByte())
            buffer.put(penaltyBytes)
        } else {
            buffer.put(0.toByte())
        }
        buffer.put(snoozeCount.toByte())
        buffer.put(status.toByte())
        buffer.put(1.toByte()) // bump
        buffer.put(2.toByte()) // vault_bump
        buffer.put(ByteArray(32))

        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun discriminator(name: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(name.toByteArray()).copyOfRange(0, 8)
    }
}
