package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PenaltyRoute enum and SolarmaTreasury constants.
 * These map directly to Rust on-chain penalty route validation in slash.rs.
 */
class PenaltyRouteTest {
    // =========================================================================
    // PenaltyRoute codes must match Rust constants
    // =========================================================================

    @Test
    fun `BURN route code is 0`() {
        assertEquals(0.toByte(), PenaltyRoute.BURN.code)
    }

    @Test
    fun `DONATE route code is 1`() {
        assertEquals(1.toByte(), PenaltyRoute.DONATE.code)
    }

    @Test
    fun `BUDDY route code is 2`() {
        assertEquals(2.toByte(), PenaltyRoute.BUDDY.code)
    }

    @Test
    fun `all routes have unique codes`() {
        val codes = PenaltyRoute.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `exactly 3 penalty routes exist`() {
        assertEquals(3, PenaltyRoute.entries.size)
    }

    // =========================================================================
    // PenaltyRoute.fromCode
    // =========================================================================

    @Test
    fun `fromCode 0 returns BURN`() {
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(0))
    }

    @Test
    fun `fromCode 1 returns DONATE`() {
        assertEquals(PenaltyRoute.DONATE, PenaltyRoute.fromCode(1))
    }

    @Test
    fun `fromCode 2 returns BUDDY`() {
        assertEquals(PenaltyRoute.BUDDY, PenaltyRoute.fromCode(2))
    }

    @Test
    fun `fromCode unknown defaults to BURN`() {
        // Critical: unknown route should default to BURN (safest)
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(3))
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(255))
        assertEquals(PenaltyRoute.BURN, PenaltyRoute.fromCode(-1))
    }

    @Test
    fun `fromCode is inverse of code for valid values`() {
        for (route in PenaltyRoute.entries) {
            assertEquals(route, PenaltyRoute.fromCode(route.code.toInt()))
        }
    }

    // =========================================================================
    // SolarmaTreasury
    // =========================================================================

    @Test
    fun `treasury address is valid Base58`() {
        val addr = SolarmaTreasury.ADDRESS
        assertTrue("Treasury address should not be empty", addr.isNotEmpty())
        // Valid Base58 characters (no 0, O, I, l)
        val invalidChars = setOf('0', 'O', 'I', 'l')
        for (c in addr) {
            assertFalse(
                "Character '$c' is not valid Base58",
                c in invalidChars
            )
        }
    }

    @Test
    fun `treasury address length is valid Solana address`() {
        // Solana addresses are 32-43 chars in Base58
        val len = SolarmaTreasury.ADDRESS.length
        assertTrue("Address too short: $len", len >= 32)
        assertTrue("Address too long: $len", len <= 44)
    }

    @Test
    fun `BURN_SINK address is valid incinerator`() {
        val sink = TransactionBuilder.BURN_SINK.toBase58()
        assertEquals("1nc1nerator11111111111111111111111111111111", sink)
    }
}
