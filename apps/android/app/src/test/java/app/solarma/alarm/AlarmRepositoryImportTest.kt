package app.solarma.alarm

import android.content.Context
import app.solarma.data.local.AlarmDao
import app.solarma.data.local.AlarmEntity
import app.solarma.data.local.StatsDao
import app.solarma.wallet.OnchainAlarmAccount
import app.solarma.wallet.ProgramAccount
import app.solarma.wallet.SolanaRpcClient
import app.solarma.wallet.TransactionQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Integration tests for AlarmRepository.importOnchainAlarms.
 * Uses an injectable parser function to test the real import logic
 * without Borsh deserialization.
 */
class AlarmRepositoryImportTest {

    private lateinit var context: Context
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var statsDao: StatsDao
    private lateinit var transactionQueue: TransactionQueue
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var repo: AlarmRepository

    private val owner = "owner_pubkey_base58"
    private val programId = "51AE" // abbreviated

    private fun makeAccount(
        pubkey: String = "alarm_pda_1",
        ownerAddr: String = owner,
        status: Int = 0,
        remaining: Long = 1_000_000_000L,
        alarmTimeUnix: Long = System.currentTimeMillis() / 1000 + 3600 // 1h from now
    ) = OnchainAlarmAccount(
        pubkey = pubkey,
        owner = ownerAddr,
        alarmTimeUnix = alarmTimeUnix,
        deadlineUnix = alarmTimeUnix + AlarmTiming.GRACE_PERIOD_SECONDS,
        initialAmount = 2_000_000_000L,
        remainingAmount = remaining,
        penaltyRoute = 0,
        penaltyDestination = null,
        snoozeCount = 0,
        status = status
    )

    @Before
    fun setUp() {
        context = mock()
        alarmDao = mock()
        alarmScheduler = mock()
        statsDao = mock()
        transactionQueue = mock()
        rpcClient = mock()

        repo = AlarmRepository(
            context = context,
            alarmDao = alarmDao,
            alarmScheduler = alarmScheduler,
            statsDao = statsDao,
            transactionQueue = transactionQueue,
            rpcClient = rpcClient
        )
    }

    private fun setParser(vararg accounts: Pair<String, OnchainAlarmAccount?>) {
        val map = accounts.toMap()
        repo.parseAlarm = { pubkey, _ -> map[pubkey] }
    }

    private suspend fun setupRpc(accounts: List<ProgramAccount>) {
        whenever(rpcClient.getProgramAccounts(any(), eq(owner)))
            .thenReturn(Result.success(accounts))
    }

    // ── Import filtering ──

    @Test
    fun `status 0 with positive remaining is imported`() = runTest {
        val parsed = makeAccount()
        setParser("pda1" to parsed)
        setupRpc(listOf(ProgramAccount("pda1", "")))
        whenever(alarmDao.getByOnchainPubkey("alarm_pda_1")).thenReturn(null)
        whenever(alarmDao.insert(any())).thenReturn(1L)

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        verify(alarmDao).insert(any())
        verify(alarmScheduler).schedule(eq(1L), any())
    }

    @Test
    fun `status 1 Acknowledged is skipped`() = runTest {
        val parsed = makeAccount(status = 1)
        setParser("pda1" to parsed)
        setupRpc(listOf(ProgramAccount("pda1", "")))

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
        verify(alarmDao, never()).insert(any())
    }

    @Test
    fun `status 2 Claimed is skipped`() = runTest {
        val parsed = makeAccount(status = 2, remaining = 0)
        setParser("pda1" to parsed)
        setupRpc(listOf(ProgramAccount("pda1", "")))

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `owner mismatch is skipped`() = runTest {
        val parsed = makeAccount(ownerAddr = "different_owner")
        setParser("pda1" to parsed)
        setupRpc(listOf(ProgramAccount("pda1", "")))

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `existing alarm is updated not duplicated`() = runTest {
        val parsed = makeAccount()
        setParser("pda1" to parsed)
        setupRpc(listOf(ProgramAccount("pda1", "")))

        val existing = AlarmEntity(
            id = 42,
            alarmTimeMillis = 0,
            label = "Old",
            isEnabled = false,
            repeatDays = 0,
            wakeProofType = 1,
            targetSteps = 20,
            hasDeposit = false,
            depositAmount = 0.0,
            depositLamports = 0,
            penaltyRoute = 0,
            penaltyDestination = null,
            onchainPubkey = "alarm_pda_1",
            onchainAlarmId = null,
            snoozeCount = 0
        )
        whenever(alarmDao.getByOnchainPubkey("alarm_pda_1")).thenReturn(existing)

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(0, result.imported)
        assertEquals(1, result.updated)
        verify(alarmDao).update(any())
        verify(alarmDao, never()).insert(any())
    }

    @Test
    fun `past alarm is imported but scheduler not called`() = runTest {
        val pastAlarm = makeAccount(alarmTimeUnix = 1_000_000_000L) // 2001
        setParser("pda1" to pastAlarm)
        setupRpc(listOf(ProgramAccount("pda1", "")))
        whenever(alarmDao.getByOnchainPubkey("alarm_pda_1")).thenReturn(null)
        whenever(alarmDao.insert(any())).thenReturn(1L)

        val result = repo.importOnchainAlarms(owner).getOrThrow()

        assertEquals(1, result.imported)
        // Past alarm: isEnabled = false, scheduler NOT called
        verify(alarmScheduler, never()).schedule(any(), any())
    }
}
