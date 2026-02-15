package app.solarma.wallet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Integration tests for OnchainAlarmService.awaitConfirmation (now internal).
 * Uses a mocked SolanaRpcClient to test the confirmation polling loop.
 */
class OnchainAlarmServiceIntegrationTest {

    private lateinit var networkChecker: NetworkChecker
    private lateinit var walletManager: WalletManager
    private lateinit var transactionBuilder: TransactionBuilder
    private lateinit var rpcClient: SolanaRpcClient
    private lateinit var service: OnchainAlarmService

    @Before
    fun setUp() {
        networkChecker = mock { on { isNetworkAvailable() } doReturn true }
        walletManager = mock()
        transactionBuilder = mock()
        rpcClient = mock()
        service = OnchainAlarmService(
            networkChecker = networkChecker,
            walletManager = walletManager,
            transactionBuilder = transactionBuilder,
            rpcClient = rpcClient
        )
    }

    @Test
    fun `awaitConfirmation returns Confirmed on confirmed status`() = runTest {
        whenever(rpcClient.getSignatureStatus("sig_ok"))
            .thenReturn(Result.success(SignatureStatus("confirmed", null)))

        val result = service.awaitConfirmation("sig_ok")

        assertTrue(result is OnchainAlarmService.ConfirmationResult.Confirmed)
    }

    @Test
    fun `awaitConfirmation returns Failed on status with error`() = runTest {
        whenever(rpcClient.getSignatureStatus("sig_err"))
            .thenReturn(Result.success(SignatureStatus("processed", "InstructionError")))

        val result = service.awaitConfirmation("sig_err")

        assertTrue(result is OnchainAlarmService.ConfirmationResult.Failed)
        assertEquals("InstructionError", (result as OnchainAlarmService.ConfirmationResult.Failed).error)
    }

    @Test
    fun `awaitConfirmation returns Pending when all attempts return no confirmation`() = runTest {
        // SignatureStatus with null confirmationStatus and null err → not confirmed, not failed
        whenever(rpcClient.getSignatureStatus("sig_pending"))
            .thenReturn(Result.success(SignatureStatus(null, null)))

        val result = service.awaitConfirmation("sig_pending")

        assertTrue(result is OnchainAlarmService.ConfirmationResult.Pending)
    }
}
