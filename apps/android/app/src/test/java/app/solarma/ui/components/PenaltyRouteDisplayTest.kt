package app.solarma.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [PenaltyRouteDisplay] — verifies mapping matches Anchor PenaltyRoute enum.
 */
class PenaltyRouteDisplayTest {
    @Test
    fun `route 0 maps to Burn`() {
        val info = PenaltyRouteDisplay.fromRoute(0)
        assertEquals("🔥", info.emoji)
        assertEquals("Burn", info.label)
    }

    @Test
    fun `route 1 maps to Donate`() {
        val info = PenaltyRouteDisplay.fromRoute(1)
        assertEquals("🎁", info.emoji)
        assertEquals("Donate", info.label)
    }

    @Test
    fun `route 2 maps to Buddy`() {
        val info = PenaltyRouteDisplay.fromRoute(2)
        assertEquals("👋", info.emoji)
        assertEquals("Buddy", info.label)
    }

    @Test
    fun `unknown route returns empty emoji and Unknown label`() {
        val info = PenaltyRouteDisplay.fromRoute(99)
        assertEquals("", info.emoji)
        assertEquals("Unknown", info.label)
    }

    @Test
    fun `formatted string for Burn`() {
        assertEquals("🔥 Burn", PenaltyRouteDisplay.fromRoute(0).formatted)
    }

    @Test
    fun `negative route returns Unknown`() {
        val info = PenaltyRouteDisplay.fromRoute(-1)
        assertEquals("Unknown", info.label)
    }
}
