package app.solarma.ui.settings

import app.solarma.alarm.ImportResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SettingsViewModel state classes and validation logic.
 * Tests SettingsUiState data class and ImportState sealed class.
 */
class SettingsViewModelTest {
    // ── SettingsUiState default values ──

    @Test
    fun `default SettingsUiState has expected defaults`() {
        val state = SettingsUiState()
        assertNull(state.walletAddress)
        assertTrue(state.isDevnet)
        assertEquals(50, state.defaultSteps)
        assertEquals(0.1, state.defaultDepositSol, 0.001)
        assertEquals(0, state.defaultPenalty)
        assertFalse(state.nfcTagRegistered)
        assertNull(state.nfcTagHash)
        assertFalse(state.qrCodeRegistered)
        assertNull(state.qrCode)
        assertEquals("Sunrise Glow", state.soundName)
        assertTrue(state.vibrationEnabled)
    }

    @Test
    fun `SettingsUiState copy modifies single field`() {
        val original = SettingsUiState()
        val modified = original.copy(walletAddress = "8xrt45...")
        assertEquals("8xrt45...", modified.walletAddress)
        assertNull(original.walletAddress)
    }

    @Test
    fun `SettingsUiState copy modifies wallet address`() {
        val state = SettingsUiState().copy(
            walletAddress = "8xrt45..."
        )
        assertEquals("8xrt45...", state.walletAddress)
    }

    @Test
    fun `SettingsUiState equality`() {
        val a = SettingsUiState()
        val b = SettingsUiState()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SettingsUiState inequality on different field`() {
        val a = SettingsUiState(defaultSteps = 20)
        val b = SettingsUiState(defaultSteps = 50)
        assertNotEquals(a, b)
    }

    @Test
    fun `SettingsUiState with NFC tag`() {
        val state = SettingsUiState(nfcTagRegistered = true, nfcTagHash = "abc123")
        assertTrue(state.nfcTagRegistered)
        assertEquals("abc123", state.nfcTagHash)
    }

    @Test
    fun `SettingsUiState with QR code`() {
        val state = SettingsUiState(qrCodeRegistered = true, qrCode = "SOLARMA-ABC12345")
        assertTrue(state.qrCodeRegistered)
        assertEquals("SOLARMA-ABC12345", state.qrCode)
    }

    @Test
    fun `SettingsUiState devnet toggle`() {
        val devnet = SettingsUiState(isDevnet = true)
        val mainnet = devnet.copy(isDevnet = false)
        assertTrue(devnet.isDevnet)
        assertFalse(mainnet.isDevnet)
    }

    @Test
    fun `SettingsUiState vibration toggle`() {
        val enabled = SettingsUiState(vibrationEnabled = true)
        val disabled = enabled.copy(vibrationEnabled = false)
        assertTrue(enabled.vibrationEnabled)
        assertFalse(disabled.vibrationEnabled)
    }

    @Test
    fun `SettingsUiState deposit range`() {
        val small = SettingsUiState(defaultDepositSol = 0.001)
        val large = SettingsUiState(defaultDepositSol = 100.0)
        assertTrue(small.defaultDepositSol < large.defaultDepositSol)
    }

    @Test
    fun `SettingsUiState penalty routes 0 to 2`() {
        for (penalty in 0..2) {
            val state = SettingsUiState(defaultPenalty = penalty)
            assertEquals(penalty, state.defaultPenalty)
        }
    }

    @Test
    fun `SettingsUiState sound name`() {
        val state = SettingsUiState(soundName = "Ocean Wave")
        assertEquals("Ocean Wave", state.soundName)
    }

    @Test
    fun `SettingsUiState all NFC states`() {
        // Not registered
        val noNfc = SettingsUiState(nfcTagRegistered = false, nfcTagHash = null)
        assertFalse(noNfc.nfcTagRegistered)
        assertNull(noNfc.nfcTagHash)

        // Registered
        val withNfc = SettingsUiState(nfcTagRegistered = true, nfcTagHash = "deadbeef")
        assertTrue(withNfc.nfcTagRegistered)
        assertEquals("deadbeef", withNfc.nfcTagHash)
    }

    @Test
    fun `SettingsUiState toString contains all fields`() {
        val state = SettingsUiState(walletAddress = "test123")
        val str = state.toString()
        assertTrue(str.contains("test123"))
        assertTrue(str.contains("isDevnet"))
    }

    // ── ImportState sealed class ──

    @Test
    fun `ImportState Idle is singleton`() {
        assertSame(ImportState.Idle, ImportState.Idle)
    }

    @Test
    fun `ImportState Loading is singleton`() {
        assertSame(ImportState.Loading, ImportState.Loading)
    }

    @Test
    fun `ImportState Success carries ImportResult`() {
        val result = ImportResult(imported = 3, updated = 1, skipped = 2)
        val state = ImportState.Success(result)
        assertEquals(3, state.result.imported)
        assertEquals(1, state.result.updated)
        assertEquals(2, state.result.skipped)
    }

    @Test
    fun `ImportState Success with zero imports`() {
        val result = ImportResult(imported = 0, updated = 0, skipped = 5)
        val state = ImportState.Success(result)
        assertEquals(0, state.result.imported)
        assertEquals(5, state.result.skipped)
    }

    @Test
    fun `ImportState Error carries message`() {
        val state = ImportState.Error("RPC failed")
        assertEquals("RPC failed", state.message)
    }

    @Test
    fun `ImportState all types distinct`() {
        val result = ImportResult(imported = 1, updated = 0, skipped = 0)
        val states = listOf(
            ImportState.Idle,
            ImportState.Loading,
            ImportState.Success(result),
            ImportState.Error("e")
        )
        assertEquals(4, states.toSet().size)
    }

    @Test
    fun `ImportState types are ImportState subclasses`() {
        val result = ImportResult(imported = 0, updated = 0, skipped = 0)
        assertTrue(ImportState.Idle is ImportState)
        assertTrue(ImportState.Loading is ImportState)
        assertTrue(ImportState.Success(result) is ImportState)
        assertTrue(ImportState.Error("x") is ImportState)
    }

    @Test
    fun `ImportState when expression covers all cases`() {
        val result = ImportResult(imported = 2, updated = 1, skipped = 0)
        val states = listOf<ImportState>(
            ImportState.Idle,
            ImportState.Loading,
            ImportState.Success(result),
            ImportState.Error("err")
        )
        for (state in states) {
            val label = when (state) {
                is ImportState.Idle -> "idle"
                is ImportState.Loading -> "loading"
                is ImportState.Success -> "imported:${state.result.imported}"
                is ImportState.Error -> "error:${state.message}"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    @Test
    fun `ImportState Error equality`() {
        assertEquals(ImportState.Error("x"), ImportState.Error("x"))
        assertNotEquals(ImportState.Error("x"), ImportState.Error("y"))
    }

    // ── ImportResult data class ──

    @Test
    fun `ImportResult equality`() {
        val a = ImportResult(imported = 1, updated = 2, skipped = 3)
        val b = ImportResult(imported = 1, updated = 2, skipped = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ImportResult inequality`() {
        val a = ImportResult(imported = 1, updated = 2, skipped = 3)
        val b = ImportResult(imported = 1, updated = 2, skipped = 4)
        assertNotEquals(a, b)
    }

    @Test
    fun `ImportResult copy`() {
        val original = ImportResult(imported = 1, updated = 2, skipped = 3)
        val modified = original.copy(imported = 5)
        assertEquals(5, modified.imported)
        assertEquals(2, modified.updated)
        assertEquals(1, original.imported)
    }

    @Test
    fun `ImportResult destructuring`() {
        val result = ImportResult(imported = 1, updated = 2, skipped = 3)
        val (imported, updated, skipped) = result
        assertEquals(1, imported)
        assertEquals(2, updated)
        assertEquals(3, skipped)
    }

    @Test
    fun `ImportResult total count`() {
        val result = ImportResult(imported = 5, updated = 3, skipped = 2)
        val total = result.imported + result.updated + result.skipped
        assertEquals(10, total)
    }
}
