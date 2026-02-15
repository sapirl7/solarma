package app.solarma.wakeproof

import app.solarma.data.local.AlarmEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for WakeProofEngine — the core state-machine that enforces
 * wake proof challenges (Steps / NFC / QR / None).
 *
 * Uses UnconfinedTestDispatcher so coroutines advance eagerly,
 * and backgroundScope to prevent UncompletedCoroutinesError from
 * StateFlow.collect (which never completes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeProofEngineTest {
    private lateinit var stepCounter: StepCounter
    private lateinit var nfcScanner: NfcScanner
    private lateinit var qrScanner: QrScanner
    private lateinit var engine: WakeProofEngine

    private val nfcScanResult = MutableStateFlow<NfcScanResult>(NfcScanResult.Idle)
    private val qrScanResult = MutableStateFlow<QrScanResult>(QrScanResult.Idle)

    private fun alarm(
        proofType: Int = WakeProofEngine.TYPE_NONE,
        targetSteps: Int = 20,
        tagHash: String? = null,
        qrCode: String? = null,
        hasDeposit: Boolean = false,
    ) = AlarmEntity(
        alarmTimeMillis = System.currentTimeMillis(),
        wakeProofType = proofType,
        targetSteps = targetSteps,
        tagHash = tagHash,
        qrCode = qrCode,
        hasDeposit = hasDeposit,
    )

    @Before
    fun setUp() {
        stepCounter = mock()
        nfcScanner = mock {
            on { scanResult }.thenReturn(nfcScanResult)
        }
        qrScanner = mock {
            on { scanResult }.thenReturn(qrScanResult)
        }
        engine = WakeProofEngine(stepCounter, nfcScanner, qrScanner)
    }

    // ── TYPE_NONE ──────────────────────────────────────

    @Test
    fun `TYPE_NONE start sets requiresAction true`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NONE), backgroundScope)

            val p = engine.progress.value
            assertEquals(WakeProofEngine.TYPE_NONE, p.type)
            assertTrue("Should require action tap", p.requiresAction)
            assertFalse(engine.isComplete.value)
        }

    @Test
    fun `TYPE_NONE confirmAwake completes challenge`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NONE), backgroundScope)

            engine.confirmAwake()

            assertTrue(engine.isComplete.value)
            assertEquals(1f, engine.progress.value.progressPercent, 0.01f)
            assertTrue(engine.progress.value.message.contains("complete", ignoreCase = true))
        }

    @Test
    fun `confirmAwake is no-op when TYPE_STEPS active without fallback`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(true)
            whenever(stepCounter.countSteps(50)).thenReturn(emptyFlow())

            engine.start(alarm(proofType = WakeProofEngine.TYPE_STEPS, targetSteps = 50), backgroundScope)

            engine.confirmAwake()

            assertFalse("Should NOT complete without steps", engine.isComplete.value)
        }

    // ── TYPE_STEPS ─────────────────────────────────────

    @Test
    fun `TYPE_STEPS with available sensor sets step progress`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(true)
            whenever(stepCounter.countSteps(30)).thenReturn(emptyFlow())

            engine.start(alarm(proofType = WakeProofEngine.TYPE_STEPS, targetSteps = 30), backgroundScope)

            val p = engine.progress.value
            assertEquals(WakeProofEngine.TYPE_STEPS, p.type)
            assertEquals(30, p.targetValue)
        }

    @Test
    fun `TYPE_STEPS completes when steps reach target`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(true)
            whenever(stepCounter.countSteps(20)).thenReturn(flowOf(
                StepProgress(10, 20, 0.5f, false),
                StepProgress(20, 20, 1.0f, true),
            ))

            engine.start(alarm(proofType = WakeProofEngine.TYPE_STEPS, targetSteps = 20), backgroundScope)

            assertTrue(engine.isComplete.value)
        }

    @Test
    fun `TYPE_STEPS sensor unavailable with NO deposit offers fallback`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(false)

            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_STEPS, hasDeposit = false), backgroundScope
            )

            val p = engine.progress.value
            assertTrue("Should have fallback for non-deposit", p.fallbackActive)
            assertTrue(p.requiresAction)

            engine.confirmAwake()
            assertTrue(engine.isComplete.value)
        }

    @Test
    fun `SECURITY TYPE_STEPS sensor unavailable with deposit blocks fallback`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(false)

            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_STEPS, hasDeposit = true), backgroundScope
            )

            val p = engine.progress.value
            assertFalse("SECURITY: deposit must NOT get fallback", p.fallbackActive)
            assertFalse(p.requiresAction)

            engine.confirmAwake()
            assertFalse("SECURITY: confirmAwake must NOT work for deposit alarms", engine.isComplete.value)
        }

    // ── TYPE_NFC ───────────────────────────────────────

    @Test
    fun `TYPE_NFC with null tagHash shows error`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = null), backgroundScope)

            assertNotNull(engine.progress.value.error)
            assertTrue(engine.progress.value.message.contains("No NFC tag registered"))
        }

    @Test
    fun `TYPE_NFC with invalid hex tagHash shows error`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = "xyz"), backgroundScope)

            assertNotNull(engine.progress.value.error)
            assertTrue(engine.progress.value.message.contains("Invalid"))
        }

    @Test
    fun `TYPE_NFC with odd-length hex shows error`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = "abc"), backgroundScope)

            assertNotNull(engine.progress.value.error)
        }

    @Test
    fun `TYPE_NFC with valid tagHash starts scanning`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = "abcdef01"), backgroundScope
            )

            val p = engine.progress.value
            assertEquals(WakeProofEngine.TYPE_NFC, p.type)
            assertTrue(p.message.contains("Scan"))
        }

    @Test
    fun `TYPE_NFC completes on Success result`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = "abcdef01"), backgroundScope
            )

            nfcScanResult.value = NfcScanResult.Success

            assertTrue(engine.isComplete.value)
        }

    @Test
    fun `TYPE_NFC wrong tag does not complete`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_NFC, tagHash = "abcdef01"), backgroundScope
            )

            nfcScanResult.value = NfcScanResult.WrongTag

            assertFalse(engine.isComplete.value)
            assertNotNull(engine.progress.value.error)
        }

    // ── TYPE_QR ────────────────────────────────────────

    @Test
    fun `TYPE_QR with null code shows error`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_QR, qrCode = null), backgroundScope)

            assertNotNull(engine.progress.value.error)
            assertTrue(engine.progress.value.message.contains("No QR code registered"))
        }

    @Test
    fun `TYPE_QR completes on Success result`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_QR, qrCode = "solarma-abc"), backgroundScope
            )

            qrScanResult.value = QrScanResult.Success

            assertTrue(engine.isComplete.value)
        }

    @Test
    fun `TYPE_QR wrong code does not complete`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(
                alarm(proofType = WakeProofEngine.TYPE_QR, qrCode = "expected"), backgroundScope
            )

            qrScanResult.value = QrScanResult.WrongCode("wrong")

            assertFalse(engine.isComplete.value)
        }

    // ── Unknown type ───────────────────────────────────

    @Test
    fun `unknown type falls back to requiresAction`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = 99), backgroundScope)

            assertTrue(engine.progress.value.requiresAction)
        }

    // ── activateFallback ───────────────────────────────

    @Test
    fun `activateFallback enables manual confirm`() =
        runTest(UnconfinedTestDispatcher()) {
            whenever(stepCounter.isAvailable()).thenReturn(true)
            whenever(stepCounter.countSteps(50)).thenReturn(emptyFlow())

            engine.start(alarm(proofType = WakeProofEngine.TYPE_STEPS, targetSteps = 50), backgroundScope)

            engine.activateFallback("Sensor broken. Tap to confirm.")

            assertTrue(engine.progress.value.fallbackActive)
            assertTrue(engine.progress.value.requiresAction)
            assertNull(engine.progress.value.error)

            engine.confirmAwake()
            assertTrue(engine.isComplete.value)
        }

    // ── stop ───────────────────────────────────────────

    @Test
    fun `stop cleans up and allows restart`() =
        runTest(UnconfinedTestDispatcher()) {
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NONE), backgroundScope)
            engine.confirmAwake()
            assertTrue(engine.isComplete.value)

            engine.stop()
            verify(nfcScanner, atLeastOnce()).stopScanning()
            verify(qrScanner, atLeastOnce()).stopScanning()

            // Restart
            engine.start(alarm(proofType = WakeProofEngine.TYPE_NONE), backgroundScope)
            assertFalse(engine.isComplete.value)
        }

    // ── WakeProgress data class ────────────────────────

    @Test
    fun `WakeProgress defaults are sane`() {
        val wp = WakeProgress()
        assertEquals(WakeProofEngine.TYPE_NONE, wp.type)
        assertEquals(0, wp.currentValue)
        assertEquals(0, wp.targetValue)
        assertEquals(0f, wp.progressPercent, 0.001f)
        assertEquals("", wp.message)
        assertNull(wp.error)
        assertFalse(wp.requiresAction)
        assertFalse(wp.fallbackActive)
    }

    @Test
    fun `WakeProgress copy preserves untouched fields`() {
        val wp = WakeProgress(
            type = WakeProofEngine.TYPE_STEPS,
            currentValue = 5,
            targetValue = 20,
            progressPercent = 0.25f,
            message = "Walking",
        )
        val changed = wp.copy(message = "Done", progressPercent = 1f)
        assertEquals(WakeProofEngine.TYPE_STEPS, changed.type)
        assertEquals(5, changed.currentValue)
        assertEquals(20, changed.targetValue)
        assertEquals("Done", changed.message)
        assertEquals(1f, changed.progressPercent, 0.001f)
    }

    // ── Companion constants ────────────────────────────

    @Test
    fun `proof type constants are distinct`() {
        val types = setOf(
            WakeProofEngine.TYPE_NONE,
            WakeProofEngine.TYPE_STEPS,
            WakeProofEngine.TYPE_NFC,
            WakeProofEngine.TYPE_QR,
        )
        assertEquals("All type constants must be unique", 4, types.size)
    }
}
