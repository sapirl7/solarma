package app.solarma.wallet

import app.solarma.alarm.AlarmTiming
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

/**
 * Integration tests for TransactionProcessor.processPendingTransactionsWithUi.
 * Uses mocked dependencies to test the real processing flow:
 * deadline guards, status.err classification, and max retries.
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

    private fun makeAlarm(id: Long = 1, alarmTimeMillis: Long = System.currentTimeMillis() + 600_000) =
        AlarmEntity(
            id = id,
            alarmTimeMillis = alarmTimeMillis,
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
            onchainPubkey = "11111111111111111111111111111111",
            onchainAlarmId = 1,
            snoozeCount = 0
        )

    private fun makeTx(type: String, alarmId: Long = 1, retryCount: Int = 0) =
        PendingTransaction(
            id = 100,
            type = type,
            alarmId = alarmId,
            retryCount = retryCount
        )

    @Before
    fun setUp() {
        networkChecker = mock { on { isNetworkAvailable() } doReturn true }
        transactionDao = mock()
        walletManager = mock {
            on { isConnected() } doReturn true
            on { getConnectedWallet() } doReturn ownerAddress
        }
        transactionBuilder = mock {
            on { instructionBuilder } doReturn mock()
        }
        rpcClient = mock()
        alarmDao = mock()
        statsDao = mock()
        activityResultSender = mock()

        processor = TransactionProcessor(
            networkChecker = networkChecker,
            transactionDao = transactionDao,
            walletManager = walletManager,
            transactionBuilder = transactionBuilder,
            rpcClient = rpcClient,
            alarmDao = alarmDao,
            statsDao = statsDao
        )
    }

    // ── Deadline guard tests ──

    @Test
    fun `CLAIM after deadline is marked FAILED and builder not called`() = runTest {
        val pastAlarm = makeAlarm(alarmTimeMillis = System.currentTimeMillis() - AlarmTiming.GRACE_PERIOD_MILLIS - 1000)
        val tx = makeTx("CLAIM")
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))
        whenever(alarmDao.getById(1)).thenReturn(pastAlarm)

        processor.processPendingTransactionsWithUi(activityResultSender)

        verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("Deadline passed") }, any())
        verify(transactionBuilder, never()).buildClaimTransactionByPubkey(any(), any())
    }

    @Test
    fun `ACK_AWAKE after deadline is marked FAILED and builder not called`() = runTest {
        val pastAlarm = makeAlarm(alarmTimeMillis = System.currentTimeMillis() - AlarmTiming.GRACE_PERIOD_MILLIS - 1000)
        val tx = makeTx("ACK_AWAKE")
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))
        whenever(alarmDao.getById(1)).thenReturn(pastAlarm)

        processor.processPendingTransactionsWithUi(activityResultSender)

        verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("Deadline passed") }, any())
        verify(transactionBuilder, never()).buildAckAwakeTransactionByPubkey(any(), any())
    }

    // ── ErrorClassifier tests ──

    @Test
    fun `status_err non-retryable marks transaction FAILED`() = runTest {
        val alarm = makeAlarm()
        val tx = makeTx("CLAIM")
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))
        whenever(alarmDao.getById(1)).thenReturn(alarm)
        whenever(transactionBuilder.buildClaimTransactionByPubkey(any(), any())).thenReturn(ByteArray(64))
        whenever(walletManager.signTransaction(any(), any())).thenReturn(Result.success(ByteArray(64)))
        whenever(rpcClient.sendTransactionFanOut(any())).thenReturn(Result.success("sig123"))
        whenever(rpcClient.getSignatureStatus("sig123"))
            .thenReturn(Result.success(SignatureStatus("finalized", "InvalidAlarmState")))

        processor.processPendingTransactionsWithUi(activityResultSender)

        // InvalidAlarmState is classified as NON_RETRYABLE by ErrorClassifier
        verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), eq("InvalidAlarmState"), any())
    }

    @Test
    fun `status_err retryable keeps PENDING and increments retry`() = runTest {
        val alarm = makeAlarm()
        val tx = makeTx("CLAIM")
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))
        whenever(alarmDao.getById(1)).thenReturn(alarm)
        whenever(transactionBuilder.buildClaimTransactionByPubkey(any(), any())).thenReturn(ByteArray(64))
        whenever(walletManager.signTransaction(any(), any())).thenReturn(Result.success(ByteArray(64)))
        whenever(rpcClient.sendTransactionFanOut(any())).thenReturn(Result.success("sig456"))
        // "blockhash not found" matches ErrorClassifier rule (line 42, ignoreCase=true)
        whenever(rpcClient.getSignatureStatus("sig456"))
            .thenReturn(Result.success(SignatureStatus("processed", "blockhash not found")))

        processor.processPendingTransactionsWithUi(activityResultSender)

        verify(transactionDao).updateStatus(eq(100L), eq("PENDING"), eq("blockhash not found"), any())
        verify(transactionDao).incrementRetry(100L)
    }

    // ── Max retries ──

    @Test
    fun `max retries exceeded marks FAILED without attempting build`() = runTest {
        val tx = makeTx("CLAIM", retryCount = 5)
        whenever(transactionDao.getPendingTransactions()).thenReturn(flowOf(listOf(tx)))

        processor.processPendingTransactionsWithUi(activityResultSender)

        verify(transactionDao).updateStatus(eq(100L), eq("FAILED"), argThat { contains("Max retries") }, any())
        verify(alarmDao, never()).getById(any())
    }
}
