package app.solarma.wakeproof

import org.junit.Assert.*
import org.junit.Test

/**
 * Expanded tests for WakeProgress data class and WakeProofEngine constants.
 * WakeProgress is a plain data class with type, currentValue, targetValue,
 * progressPercent, message, error, requiresAction, and fallbackActive fields.
 */
class WakeProofExpandedTest {
    // ── WakeProgress defaults ──

    @Test
    fun `WakeProgress default values`() {
        val p = WakeProgress()
        assertEquals(WakeProofEngine.TYPE_NONE, p.type)
        assertEquals(0, p.currentValue)
        assertEquals(0, p.targetValue)
        assertEquals(0f, p.progressPercent, 0.001f)
        assertEquals("", p.message)
        assertNull(p.error)
        assertFalse(p.requiresAction)
        assertFalse(p.fallbackActive)
    }

    // ── WakeProgress copy ──

    @Test
    fun `WakeProgress copy modifies single field`() {
        val original = WakeProgress()
        val modified = original.copy(currentValue = 15)
        assertEquals(15, modified.currentValue)
        assertEquals(0, original.currentValue)
    }

    @Test
    fun `WakeProgress copy with step progress`() {
        val p = WakeProgress(
            type = WakeProofEngine.TYPE_STEPS,
            currentValue = 5,
            targetValue = 20,
            progressPercent = 0.25f,
            message = "Walk to wake up!"
        )
        val updated = p.copy(
            currentValue = 20,
            progressPercent = 1.0f,
            message = "Done!"
        )
        assertEquals(20, updated.currentValue)
        assertEquals(1.0f, updated.progressPercent, 0.001f)
        assertEquals("Done!", updated.message)
        assertEquals(5, p.currentValue) // original unchanged
    }

    // ── WakeProgress equality ──

    @Test
    fun `WakeProgress equality`() {
        val a = WakeProgress(currentValue = 10, targetValue = 20)
        val b = WakeProgress(currentValue = 10, targetValue = 20)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `WakeProgress inequality`() {
        val a = WakeProgress(currentValue = 10, targetValue = 20)
        val b = WakeProgress(currentValue = 11, targetValue = 20)
        assertNotEquals(a, b)
    }

    // ── WakeProgress with type ──

    @Test
    fun `WakeProgress with TYPE_NONE`() {
        val p = WakeProgress(
            type = WakeProofEngine.TYPE_NONE,
            requiresAction = true,
            message = "Press to confirm awake"
        )
        assertEquals(WakeProofEngine.TYPE_NONE, p.type)
        assertTrue(p.requiresAction)
    }

    @Test
    fun `WakeProgress with TYPE_STEPS`() {
        val p = WakeProgress(
            type = WakeProofEngine.TYPE_STEPS,
            currentValue = 10,
            targetValue = 20,
            progressPercent = 0.5f
        )
        assertEquals(WakeProofEngine.TYPE_STEPS, p.type)
        assertEquals(10, p.currentValue)
        assertEquals(20, p.targetValue)
        assertEquals(0.5f, p.progressPercent, 0.001f)
    }

    @Test
    fun `WakeProgress with TYPE_NFC`() {
        val p = WakeProgress(
            type = WakeProofEngine.TYPE_NFC,
            message = "Scan NFC tag",
            requiresAction = true
        )
        assertEquals(WakeProofEngine.TYPE_NFC, p.type)
        assertTrue(p.requiresAction)
    }

    @Test
    fun `WakeProgress with TYPE_QR`() {
        val p = WakeProgress(
            type = WakeProofEngine.TYPE_QR,
            message = "Scan QR code",
            requiresAction = true
        )
        assertEquals(WakeProofEngine.TYPE_QR, p.type)
        assertTrue(p.requiresAction)
    }

    // ── WakeProgress error state ──

    @Test
    fun `WakeProgress with error`() {
        val p = WakeProgress(error = "Sensor unavailable")
        assertEquals("Sensor unavailable", p.error)
    }

    @Test
    fun `WakeProgress no error by default`() {
        val p = WakeProgress()
        assertNull(p.error)
    }

    // ── WakeProgress fallback ──

    @Test
    fun `WakeProgress fallback active`() {
        val p = WakeProgress(fallbackActive = true, message = "Fallback: tap to confirm")
        assertTrue(p.fallbackActive)
        assertEquals("Fallback: tap to confirm", p.message)
    }

    @Test
    fun `WakeProgress fallback not active by default`() {
        assertFalse(WakeProgress().fallbackActive)
    }

    // ── WakeProgress progress calculation ──

    @Test
    fun `WakeProgress step completion percentage`() {
        val p = WakeProgress(currentValue = 15, targetValue = 20)
        val percentage = if (p.targetValue > 0) p.currentValue.toFloat() / p.targetValue else 0f
        assertEquals(0.75f, percentage, 0.001f)
    }

    @Test
    fun `WakeProgress zero target value`() {
        val p = WakeProgress(currentValue = 0, targetValue = 0)
        assertEquals(0, p.currentValue)
        assertEquals(0, p.targetValue)
    }

    @Test
    fun `WakeProgress complete progress`() {
        val p = WakeProgress(
            currentValue = 20,
            targetValue = 20,
            progressPercent = 1.0f
        )
        assertEquals(1.0f, p.progressPercent, 0.001f)
        assertEquals(p.currentValue, p.targetValue)
    }

    // ── WakeProgress toString ──

    @Test
    fun `WakeProgress toString contains fields`() {
        val p = WakeProgress(message = "testMsg", currentValue = 42)
        val str = p.toString()
        assertTrue(str.contains("testMsg"))
        assertTrue(str.contains("42"))
    }

    // ── WakeProgress destructuring ──

    @Test
    fun `WakeProgress destructuring`() {
        val p = WakeProgress(
            type = 1,
            currentValue = 5,
            targetValue = 20,
            progressPercent = 0.25f,
            message = "msg",
            error = "err",
            requiresAction = true,
            fallbackActive = false
        )
        val (type, current, target, progress, message, error, requires, fallback) = p
        assertEquals(1, type)
        assertEquals(5, current)
        assertEquals(20, target)
        assertEquals(0.25f, progress, 0.001f)
        assertEquals("msg", message)
        assertEquals("err", error)
        assertTrue(requires)
        assertFalse(fallback)
    }

    // ── WakeProgress type constants ──

    @Test
    fun `WakeProofEngine type constants are distinct`() {
        val types = setOf(
            WakeProofEngine.TYPE_NONE,
            WakeProofEngine.TYPE_STEPS,
            WakeProofEngine.TYPE_NFC,
            WakeProofEngine.TYPE_QR
        )
        assertEquals(4, types.size)
    }

    // ── WakeProgress state transitions ──

    @Test
    fun `WakeProgress idle to step progress to complete`() {
        // Start idle
        var state = WakeProgress()
        assertEquals(0, state.currentValue)

        // Step challenge started
        state = state.copy(
            type = WakeProofEngine.TYPE_STEPS,
            targetValue = 20,
            message = "Walk to wake up!"
        )
        assertEquals(WakeProofEngine.TYPE_STEPS, state.type)
        assertEquals(20, state.targetValue)

        // Progress
        state = state.copy(currentValue = 10, progressPercent = 0.5f)
        assertEquals(10, state.currentValue)
        assertEquals(0.5f, state.progressPercent, 0.001f)

        // Complete
        state = state.copy(currentValue = 20, progressPercent = 1.0f, message = "Done!")
        assertEquals(20, state.currentValue)
        assertEquals(1.0f, state.progressPercent, 0.001f)
    }

    @Test
    fun `WakeProgress NFC fallback transition`() {
        // NFC challenge
        var state = WakeProgress(
            type = WakeProofEngine.TYPE_NFC,
            requiresAction = true,
            message = "Scan NFC tag"
        )
        assertFalse(state.fallbackActive)

        // Fallback activated after timeout
        state = state.copy(
            fallbackActive = true,
            message = "Can't read NFC? Tap to confirm manually"
        )
        assertTrue(state.fallbackActive)
    }
}
