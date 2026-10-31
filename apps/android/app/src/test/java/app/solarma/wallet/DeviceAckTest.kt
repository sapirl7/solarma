package app.solarma.wallet

import app.solarma.wallet.DeviceAckService.Decision
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.sol4k.Keypair
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device ack key: when it may settle an alarm, the instructions it uses, its
 * transaction signing, and its fee-balance thresholds.
 */
class DeviceAckTest {
    private val service =
        DeviceAckService(
            deviceKeyStore = mock(),
            rpcClient = mock(),
            transactionBuilder = mock(),
            alarmDao = mock(),
            statsDao = mock(),
            transactionQueue = mock(),
        )
    private val instructionBuilder = SolarmaInstructionBuilder()
    private val txBuilder = TransactionBuilder(SolanaRpcClient(), instructionBuilder)

    private val owner = "11111111111111111111111111111111"
    // A real 32-byte key (a short base58 string like "333…3" decodes to fewer bytes)
    private val device = PublicKey(ByteArray(32) { 3 }).toBase58()
    private val alarmTime = 1_700_000_000L
    private val deadline = alarmTime + 1_800L

    private fun account(
        status: Int = OnchainParameters.STATUS_CREATED,
        ackAuthority: String? = device,
    ) = OnchainAlarmAccount(
        pubkey = "pda",
        owner = owner,
        alarmTimeUnix = alarmTime,
        deadlineUnix = deadline,
        initialAmount = 1L,
        remainingAmount = 1L,
        penaltyRoute = 0,
        penaltyDestination = null,
        snoozeCount = 0,
        status = status,
        ackAuthority = ackAuthority,
    )

    // ── decide ──

    @Test
    fun `device key sends inside the window`() {
        assertEquals(Decision.SEND, service.decide(account(), device, alarmTime))
        assertEquals(Decision.SEND, service.decide(account(), device, deadline - 1))
    }

    @Test
    fun `device key waits for alarm time and gives up after the deadline`() {
        assertEquals(Decision.WAIT, service.decide(account(), device, alarmTime - 1))
        assertEquals(Decision.TOO_LATE, service.decide(account(), device, deadline))
    }

    @Test
    fun `alarm bound to another key or none needs the wallet`() {
        assertEquals(Decision.NEEDS_WALLET, service.decide(account(ackAuthority = owner), device, alarmTime))
        assertEquals(Decision.NEEDS_WALLET, service.decide(account(ackAuthority = null), device, alarmTime))
    }

    @Test
    fun `acknowledged alarm is left to the wallet claim or sweep`() {
        val acked = account(status = OnchainParameters.STATUS_ACKNOWLEDGED)
        assertEquals(Decision.NEEDS_WALLET, service.decide(acked, device, alarmTime))
    }

    // ── instructions ──

    @Test
    fun `ack_and_claim has the owner as rent recipient and the device as the only signer`() {
        val ownerKey = PublicKey(owner)
        val deviceKey = PublicKey(device)
        val pda = instructionBuilder.deriveAlarmPda(ownerKey, 7L).address
        val ix = instructionBuilder.buildAckAndClaim(signer = deviceKey, alarmPda = pda, owner = ownerKey)

        // sha256("global:ack_and_claim")[0..8]
        assertEquals("c414b3a68dfe3de7", ix.data.joinToString("") { "%02x".format(it) })
        assertEquals(5, ix.accounts.size)
        assertEquals(ownerKey, ix.accounts[2].pubkey)
        assertTrue(ix.accounts[2].isWritable)
        assertFalse(ix.accounts[2].isSigner)
        assertEquals(deviceKey, ix.accounts[3].pubkey)
        assertTrue(ix.accounts[3].isSigner)
        assertEquals(1, ix.accounts.count { it.isSigner })
    }

    @Test
    fun `system transfer encodes index 2 and lamports little-endian`() {
        val from = PublicKey(owner)
        val to = PublicKey(device)
        val ix = instructionBuilder.buildSystemTransfer(from, to, 2_000_000L)

        val expected = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putLong(2_000_000L).array()
        assertArrayEquals(expected, ix.data)
        assertEquals(SYSTEM_PROGRAM_ID, ix.programId)
        assertTrue(ix.accounts[0].isSigner && ix.accounts[0].isWritable)
        assertTrue(!ix.accounts[1].isSigner && ix.accounts[1].isWritable)
    }

    @Test
    fun `create_alarm carries the ack authority`() {
        val ownerKey = PublicKey(owner)
        val deviceKey = PublicKey(device)
        val ix =
            instructionBuilder.buildCreateAlarm(
                owner = ownerKey,
                alarmId = 1L,
                alarmTime = alarmTime,
                deadline = deadline,
                depositLamports = 1_000_000L,
                penaltyRoute = 0,
                ackAuthority = deviceKey,
            )
        // ... route, penalty_destination None (1 byte), ack_authority Some (1 + 32)
        assertEquals(1.toByte(), ix.data[42])
        assertArrayEquals(deviceKey.bytes(), ix.data.copyOfRange(43, 75))
    }

    // ── signing ──

    @Test
    fun `device signature fills the only signature slot and keeps the message`() {
        val keypair = Keypair.generate()
        val pda = instructionBuilder.deriveAlarmPda(PublicKey(owner), 7L).address
        val ix = instructionBuilder.buildAckAndClaim(signer = keypair.publicKey, alarmPda = pda, owner = PublicKey(owner))
        val unsigned =
            txBuilder.buildUnsignedTransactionForSnapshot(
                keypair.publicKey,
                ix,
                "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR",
            )

        val signed = txBuilder.signAsOnlySigner(unsigned, keypair)

        assertEquals(unsigned.size, signed.size)
        assertEquals(1, signed[0].toInt())
        assertArrayEquals(unsigned.copyOfRange(65, unsigned.size), signed.copyOfRange(65, signed.size))
        assertNotEquals(0, signed.copyOfRange(1, 65).count { it.toInt() != 0 })
        // Ed25519 is deterministic
        assertArrayEquals(signed, txBuilder.signAsOnlySigner(unsigned, keypair))
    }

    // ── fee balance ──

    @Test
    fun `device key fee thresholds respect the rent-exempt minimum`() {
        assertTrue(DeviceKeyStore.needsTopUp(0))
        assertFalse(DeviceKeyStore.canPayAck(DeviceKeyStore.RENT_EXEMPT_MIN_LAMPORTS))
        assertTrue(DeviceKeyStore.canPayAck(DeviceKeyStore.RENT_EXEMPT_MIN_LAMPORTS + DeviceKeyStore.MAX_ACK_FEE_LAMPORTS))
        // One top-up leaves room for many acks
        assertFalse(DeviceKeyStore.needsTopUp(DeviceKeyStore.TOP_UP_LAMPORTS))
    }
}
