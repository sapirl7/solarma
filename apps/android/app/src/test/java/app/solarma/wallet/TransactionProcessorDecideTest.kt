package app.solarma.wallet

import app.solarma.wallet.TransactionProcessor.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for [TransactionProcessor.decide]: what a queued transaction does given the
 * on-chain alarm state. Pure function, no I/O.
 */
class TransactionProcessorDecideTest {
    private val processor =
        TransactionProcessor(
            networkChecker = mock(),
            transactionDao = mock(),
            walletManager = mock(),
            transactionBuilder = mock(),
            rpcClient = mock(),
            alarmDao = mock(),
            statsDao = mock(),
        )

    private val owner = "11111111111111111111111111111111"
    private val buddy = "33333333333333333333333333333333"
    private val alarmTime = 1_700_000_000L
    private val deadline = alarmTime + 1_800L

    private fun account(
        status: Int = OnchainParameters.STATUS_CREATED,
        snoozeCount: Int = 0,
        penaltyRoute: Int = 0,
        penaltyDestination: String? = null,
    ) = OnchainAlarmAccount(
        pubkey = "pda",
        owner = owner,
        alarmTimeUnix = alarmTime,
        deadlineUnix = deadline,
        initialAmount = 100_000_000L,
        remainingAmount = 100_000_000L,
        penaltyRoute = penaltyRoute,
        penaltyDestination = penaltyDestination,
        snoozeCount = snoozeCount,
        status = status,
    )

    private fun decide(
        type: String,
        onchain: OnchainAlarmAccount?,
        now: Long,
        caller: String = owner,
    ) = processor.decide(type, onchain, now, caller)

    // ── Resolved ──

    @Test
    fun `missing account means resolved for every type`() {
        for (type in listOf("ACK_AND_CLAIM", "CLAIM", "ACK_AWAKE", "SWEEP", "SNOOZE", "SLASH", "EMERGENCY_REFUND")) {
            assertEquals(type, Action.Resolved, decide(type, null, alarmTime))
        }
    }

    @Test
    fun `legacy claimed or slashed status means resolved`() {
        assertEquals(Action.Resolved, decide("ACK_AND_CLAIM", account(status = 2), alarmTime))
        assertEquals(Action.Resolved, decide("SLASH", account(status = 3), deadline))
    }

    // ── Ack + claim / claim / sweep ──

    @Test
    fun `ack and claim before alarm time waits`() {
        assertTrue(decide("ACK_AND_CLAIM", account(), alarmTime - 1) is Action.Wait)
    }

    @Test
    fun `ack and claim inside the window acks and claims atomically`() {
        assertEquals(Action.AckAndClaim, decide("ACK_AND_CLAIM", account(), alarmTime))
        assertEquals(Action.AckAndClaim, decide("ACK_AND_CLAIM", account(), deadline - 1))
    }

    @Test
    fun `legacy ack and claim types map to the combined transaction`() {
        assertEquals(Action.AckAndClaim, decide("ACK_AWAKE", account(), alarmTime + 60))
        assertEquals(Action.AckAndClaim, decide("CLAIM", account(), alarmTime + 60))
    }

    @Test
    fun `unacknowledged alarm at deadline can no longer be claimed`() {
        assertTrue(decide("ACK_AND_CLAIM", account(), deadline) is Action.Fail)
    }

    @Test
    fun `acknowledged alarm claims through the grace window then sweeps`() {
        val acked = account(status = OnchainParameters.STATUS_ACKNOWLEDGED)
        assertEquals(Action.Claim, decide("ACK_AND_CLAIM", acked, deadline))
        assertEquals(Action.Claim, decide("CLAIM", acked, deadline + OnchainParameters.CLAIM_GRACE_SECONDS))
        assertEquals(Action.Sweep, decide("SWEEP", acked, deadline + OnchainParameters.CLAIM_GRACE_SECONDS + 1))
    }

    // ── Snooze ──

    @Test
    fun `snooze uses the on-chain snooze count`() {
        assertEquals(Action.Snooze(3), decide("SNOOZE", account(snoozeCount = 3), alarmTime + 10))
    }

    @Test
    fun `snooze outside its window or at the limit is not sent`() {
        assertTrue(decide("SNOOZE", account(), alarmTime - 1) is Action.Wait)
        assertTrue(decide("SNOOZE", account(), deadline) is Action.Fail)
        assertTrue(decide("SNOOZE", account(snoozeCount = OnchainParameters.MAX_SNOOZE_COUNT), alarmTime) is Action.Fail)
        assertTrue(decide("SNOOZE", account(status = OnchainParameters.STATUS_ACKNOWLEDGED), alarmTime) is Action.Fail)
    }

    // ── Slash ──

    @Test
    fun `slash waits for the deadline`() {
        assertTrue(decide("SLASH", account(), deadline - 1) is Action.Wait)
        assertEquals(Action.Slash, decide("SLASH", account(), deadline))
    }

    @Test
    fun `buddy route slash by non-buddy waits out the buddy-only window`() {
        val buddyAlarm = account(penaltyRoute = 2, penaltyDestination = buddy)
        assertTrue(decide("SLASH", buddyAlarm, deadline, caller = owner) is Action.Wait)
        assertEquals(Action.Slash, decide("SLASH", buddyAlarm, deadline, caller = buddy))
        assertEquals(
            Action.Slash,
            decide("SLASH", buddyAlarm, deadline + OnchainParameters.BUDDY_ONLY_SECONDS, caller = owner),
        )
    }

    @Test
    fun `slash of an acknowledged alarm returns the deposit instead`() {
        val acked = account(status = OnchainParameters.STATUS_ACKNOWLEDGED)
        assertEquals(Action.Claim, decide("SLASH", acked, deadline))
        assertEquals(Action.Sweep, decide("SLASH", acked, deadline + OnchainParameters.CLAIM_GRACE_SECONDS + 1))
    }

    // ── Emergency refund ──

    @Test
    fun `refund only before alarm time`() {
        assertEquals(Action.Refund, decide("EMERGENCY_REFUND", account(), alarmTime - 1))
        assertTrue(decide("EMERGENCY_REFUND", account(), alarmTime) is Action.Fail)
    }

    @Test
    fun `unknown type fails`() {
        assertTrue(decide("NOPE", account(), alarmTime) is Action.Fail)
    }
}
