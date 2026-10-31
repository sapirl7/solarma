package app.solarma.wallet

import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.data.local.StatsEntity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.sol4k.PublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Integration tests for TransactionProcessor.processPendingTransactionsWithUi.
 * Uses mocked dependencies to test the real processing flow: decisions from
 * on-chain state, signature tracking, error classification and max retries.
 */
class TransactionProcessorIntegrationTest {
    private lateinit var networkChecker: NetworkChecker
    private lateinit var transactionDao: PendingTransactionDao
    private lateinit var walletManager: WalletManager
    private lateinit var transactionBuilder: TransactionBuilder
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var alarmDao: AlarmDao
    private lateinit var statsDao: StatsDao
    private lateinit var processor: TransactionProcessor
    private lateinit var activityResultSender: ActivityResultSender

    private val ownerAddress = "11111111111111111111111111111111"
    private val alarmPda = "33333333333333333333333333333333"
    private val nowSec get() = System.currentTimeMillis() / 1000

    private fun makeAlarm() =
        AlarmEntity(
            id = 1,
            alarmTimeMillis = System.currentTimeMillis(),
            label = "Test",
            isEnabled = true,
            repeatDays = 0,
            wakeProofType = 1,
            targetSteps = 20,
            hasDeposit = true,
            depositAmount = 1.0,
            depositLamports = 1_000_000_000L,
            penaltyRoute = 0,
            penaltyDestination = null,
            onchainPubkey = alarmPda,
            onchainAlarmId = 1,
            snoozeCount = 0,
        )

    private fun makeTx(
        type: String,
        retryCount: Int = 0,
        signature: String? = null,
        sentAtBlockHeight: Long? = null,
    ) = PendingTransaction(
        id = 100,
        type = type,
        alarmId = 1,
        retryCount = retryCount,
        signature = signature,
        sentAtBlockHeight = sentAtBlockHeight,
    )

    /** Borsh-serialized Alarm account (state.rs layout) as base64. */
    private fun alarmAccountBase64(
        alarmTime: Long,
        deadline: Long,
        status: Int = OnchainParameters.STATUS_CREATED,
    ): String {
        val discriminator = MessageDigest.getInstance("SHA-256").digest("account:Alarm".toByteArray()).copyOfRange(0, 8)
        val buffer = ByteBuffer.allocate(8 + 32 + 8 * 6 + 1 + 1 + 4 + 64).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(discriminator)
        buffer.put(PublicKey(ownerAddress).bytes())
        buffer.putLong(1L) // alarm_id
        buffer.putLong(alarmTime)
        buffer.putLong(deadline)
        buffer.putLong(1_000_000_000L) // initial_amount
        buffer.putLong(1_000_000_000L) // remaining_amount
        buffer.put(0.toByte()) // penalty_route
        buffer.put(0.toByte()) // penalty_destination: None
        buffer.put(0.toByte()) // snooze_count
        buffer.put(status.toByte())
        buffer.put(1.toByte()) // bump
        buffer.put(2.toByte()) // vault_bump
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private suspend fun givenTx(tx: PendingTransaction) {
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))
        whenever(alarmDao.getById(1)).thenReturn(makeAlarm())
    }

    @Before
    fun setUp() {
        networkChecker = mock { on { isNetworkAvailable() } doReturn true }
        transactionDao = mock()
        walletManager =
            mock {
                on { isConnected() } doReturn true
                on { getConnectedWallet() } doReturn ownerAddress
            }
        transactionBuilder =
            mock {
                on { instructionBuilder } doReturn mock()
            }
        rpcClient = mock()
        alarmDao = mock()
        statsDao = mock { onBlocking { getStatsOnce() } doReturn StatsEntity() }
        activityResultSender = mock()

        processor =
            TransactionProcessor(
                networkChecker = networkChecker,
                transactionDao = transactionDao,
                walletManager = walletManager,
                transactionBuilder = transactionBuilder,
                rpcClient = rpcClient,
                alarmDao = alarmDao,
                statsDao = statsDao,
            )
    }

    // ── Decisions from on-chain state ──

    @Test
    fun `legacy CLAIM after the on-chain deadline is FAILED and nothing is signed`() =
        runTest {
            givenTx(makeTx("CLAIM"))
            whenever(rpcClient.getAccountDataBase64(alarmPda))
                .thenReturn(Result.success(alarmAccountBase64(alarmTime = nowSec - 3_600, deadline = nowSec - 1_800)))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("Deadline passed") }, any())
            verify(walletManager, never()).signTransaction(any(), any<String>(), any<suspend () -> ByteArray>())
        }

    @Test
    fun `alarm already resolved on-chain drops the transaction and the local alarm`() =
        runTest {
            givenTx(makeTx("ACK_AND_CLAIM"))
            whenever(rpcClient.getAccountDataBase64(alarmPda)).thenReturn(Result.success(null))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateStatus(eq(100L), eq("CONFIRMED"), eq("Already resolved on-chain"), any())
            verify(alarmDao).deleteById(1L)
            verify(walletManager, never()).signTransaction(any(), any<String>(), any<suspend () -> ByteArray>())
        }

    @Test
    fun `SLASH before the deadline waits without consuming a retry`() =
        runTest {
            givenTx(makeTx("SLASH"))
            whenever(rpcClient.getAccountDataBase64(alarmPda))
                .thenReturn(Result.success(alarmAccountBase64(alarmTime = nowSec - 60, deadline = nowSec + 1_740)))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateStatus(eq(100L), eq("PENDING"), argThat { contains("Deadline not passed") }, any())
            verify(transactionDao, never()).incrementRetry(any())
        }

    // ── Signature tracking ──

    @Test
    fun `previous attempt that can still land is not signed again`() =
        runTest {
            givenTx(makeTx("SNOOZE", signature = "prevSig", sentAtBlockHeight = 1_000))
            whenever(rpcClient.getSignatureStatus("prevSig")).thenReturn(Result.success(SignatureStatus(null, null)))
            whenever(rpcClient.getBlockHeight()).thenReturn(Result.success(1_050L))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateStatus(eq(100L), eq("PENDING"), argThat { contains("Awaiting confirmation") }, any())
            verify(walletManager, never()).signTransaction(any(), any<String>(), any<suspend () -> ByteArray>())
            verify(rpcClient, never()).getAccountDataBase64(any())
        }

    @Test
    fun `previous attempt that confirmed is applied without signing again`() =
        runTest {
            givenTx(makeTx("ACK_AND_CLAIM", signature = "prevSig", sentAtBlockHeight = 1_000))
            whenever(rpcClient.getSignatureStatus("prevSig")).thenReturn(Result.success(SignatureStatus("confirmed", null)))
            // Claim closed the alarm account
            whenever(rpcClient.getAccountDataBase64(alarmPda)).thenReturn(Result.success(null))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(statsDao).addSaved(1_000_000_000L)
            verify(alarmDao).deleteById(1L)
            verify(transactionDao).updateStatus(eq(100L), eq("CONFIRMED"), isNull(), any())
            verify(walletManager, never()).signTransaction(any(), any<String>(), any<suspend () -> ByteArray>())
        }

    @Test
    fun `program error after sending marks FAILED and clears the attempt`() =
        runTest {
            givenTx(makeTx("ACK_AND_CLAIM"))
            whenever(rpcClient.getAccountDataBase64(alarmPda))
                .thenReturn(Result.success(alarmAccountBase64(alarmTime = nowSec - 60, deadline = nowSec + 1_740)))
            val signed = byteArrayOf(1) + ByteArray(64) { 7 } + ByteArray(40)
            val signature = Base58.firstSignatureOf(signed)
            whenever(walletManager.signTransaction(any(), eq(ownerAddress), any<suspend () -> ByteArray>()))
                .thenReturn(Result.success(signed))
            whenever(rpcClient.getBlockHeight()).thenReturn(Result.success(500L))
            whenever(rpcClient.sendTransactionFanOut(signed)).thenReturn(Result.success(signature))
            whenever(rpcClient.getSignatureStatus(signature))
                .thenReturn(Result.success(SignatureStatus("confirmed", """{"InstructionError":[3,{"Custom":6002}]}""")))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateSent(100L, signature, 500L)
            verify(transactionDao).updateSent(100L, null, null)
            verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("6002") }, any())
        }

    // ── Max retries ──

    @Test
    fun `max retries exceeded marks FAILED without reading the chain`() =
        runTest {
            val tx = makeTx("ACK_AND_CLAIM", retryCount = 5)
            whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))

            processor.processPendingTransactionsWithUi(activityResultSender)

            verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("Max retries") }, any())
            verify(alarmDao, never()).getById(any())
        }
}
