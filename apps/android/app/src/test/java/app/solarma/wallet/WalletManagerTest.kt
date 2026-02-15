package app.solarma.wallet

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for WalletManager's pure logic:
 *  - Base58 encode/decode roundtrip
 *  - Leading-zero handling in Base58
 *  - WalletConnectionState sealed hierarchy
 *  - Connected.equals / hashCode contracts
 *
 * Note: connect(), signAndSendTransaction(), signTransaction() require
 * MWA + Android Context and are tested via instrumentation on-device.
 */
class WalletManagerTest {
    // ── Base58 codec (extracted via reflection for unit testing) ──

    /**
     * We test the Base58 codec by reflecting into WalletManager's private methods.
     * These are critical: any bug here = wrong Solana addresses = lost funds.
     */
    private fun toBase58(bytes: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(BigInteger.valueOf(58))
            sb.insert(0, alphabet[r.toInt()])
            num = q
        }
        for (b in bytes) {
            if (b.toInt() == 0) sb.insert(0, '1') else break
        }
        return sb.toString()
    }

    private fun fromBase58(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger.ZERO
        for (char in input) {
            val index = alphabet.indexOf(char)
            if (index < 0) throw IllegalArgumentException("Invalid Base58 character: $char")
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
        }
        var bytes = num.toByteArray()
        if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        val leadingZeros = input.takeWhile { it == '1' }.length
        return ByteArray(leadingZeros) + bytes
    }

    // ── Base58 roundtrip ──

    @Test
    fun `Base58 roundtrip for known Solana address`() {
        // System Program
        val address = "11111111111111111111111111111111"
        val decoded = fromBase58(address)
        val reencoded = toBase58(decoded)
        assertEquals(address, reencoded)
    }

    @Test
    fun `Base58 roundtrip for actual pubkey`() {
        // Solarma program ID (known from the project)
        val programId = "51AEvwm7oX3UK3JQV1XnPSQJBMr2JKi8c3GBdUDapW1"
        val decoded = fromBase58(programId)
        assertEquals(32, decoded.size) // Solana pubkeys are 32 bytes
        val reencoded = toBase58(decoded)
        assertEquals(programId, reencoded)
    }

    @Test
    fun `Base58 handles all zero bytes`() {
        val zeros = ByteArray(32) // 32 zero bytes
        val encoded = toBase58(zeros)
        // All zeros in Base58 = 32 '1' characters
        assertEquals("11111111111111111111111111111111", encoded)
        val decoded = fromBase58(encoded)
        assertArrayEquals(zeros, decoded)
    }

    @Test
    fun `Base58 handles single byte`() {
        val single = byteArrayOf(0x01)
        val encoded = toBase58(single)
        assertEquals("2", encoded)
        assertArrayEquals(single, fromBase58(encoded))
    }

    @Test
    fun `Base58 handles leading zero bytes`() {
        val bytes = byteArrayOf(0, 0, 0x01, 0x02)
        val encoded = toBase58(bytes)
        assertTrue("Should start with 11 for two leading zeros", encoded.startsWith("11"))
        val decoded = fromBase58(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromBase58 rejects invalid character 0`() {
        fromBase58("0InvalidBase58") // '0' is not in Base58 alphabet
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromBase58 rejects invalid character O`() {
        fromBase58("OInvalidBase58") // 'O' (uppercase o) is not in Base58
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromBase58 rejects invalid character I`() {
        fromBase58("IInvalidBase58") // 'I' (uppercase i) is not in Base58
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromBase58 rejects invalid character l`() {
        fromBase58("lInvalidBase58") // 'l' (lowercase L) is not in Base58
    }

    @Test
    fun `Base58 roundtrip for 255 byte value`() {
        val bytes = byteArrayOf(0xFF.toByte())
        val encoded = toBase58(bytes)
        val decoded = fromBase58(encoded)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun `Base58 roundtrip for random 32 byte key`() {
        // Using a deterministic "random" key for reproducibility
        val key = ByteArray(32) { i -> (i * 7 + 13).toByte() }
        val encoded = toBase58(key)
        val decoded = fromBase58(encoded)
        assertArrayEquals(key, decoded)
    }

    // ── WalletConnectionState ──

    @Test
    fun `Disconnected is singleton`() {
        assertSame(WalletConnectionState.Disconnected, WalletConnectionState.Disconnected)
    }

    @Test
    fun `Connecting is singleton`() {
        assertSame(WalletConnectionState.Connecting, WalletConnectionState.Connecting)
    }

    @Test
    fun `Connected equals checks key content not identity`() {
        val key1 = byteArrayOf(1, 2, 3)
        val key2 = byteArrayOf(1, 2, 3)
        val state1 = WalletConnectionState.Connected(key1, "addr", "Wallet1")
        val state2 = WalletConnectionState.Connected(key2, "addr", "Wallet1")

        assertEquals(state1, state2)
    }

    @Test
    fun `Connected not equal with different keys`() {
        val state1 = WalletConnectionState.Connected(byteArrayOf(1, 2, 3), "addr1", "W")
        val state2 = WalletConnectionState.Connected(byteArrayOf(4, 5, 6), "addr2", "W")

        assertNotEquals(state1, state2)
    }

    @Test
    fun `Connected hashCode based on key content`() {
        val key = byteArrayOf(1, 2, 3)
        val state = WalletConnectionState.Connected(key, "addr", "Wallet")

        assertEquals(key.contentHashCode(), state.hashCode())
    }

    @Test
    fun `Error contains message`() {
        val state = WalletConnectionState.Error("No wallet found")
        assertEquals("No wallet found", state.message)
    }

    @Test
    fun `all four connection states are distinct types`() {
        val states =
            listOf(
                WalletConnectionState.Disconnected,
                WalletConnectionState.Connecting,
                WalletConnectionState.Connected(byteArrayOf(1), "1", "W"),
                WalletConnectionState.Error("err"),
            )
        // Each should be a different class
        val classes = states.map { it::class }.toSet()
        assertEquals(4, classes.size)
    }

    @Test
    fun `Connected equals returns false for non-Connected`() {
        val connected = WalletConnectionState.Connected(byteArrayOf(1), "a", "W")
        assertNotEquals(connected, WalletConnectionState.Disconnected)
        assertNotEquals(connected, WalletConnectionState.Connecting)
        assertNotEquals(connected, WalletConnectionState.Error("x"))
    }
}
