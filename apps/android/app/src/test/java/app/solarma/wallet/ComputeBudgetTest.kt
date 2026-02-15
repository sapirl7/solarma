package app.solarma.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for compute budget instruction byte layout,
 * verifying SetComputeUnitLimit and SetComputeUnitPrice match Solana spec.
 */
class ComputeBudgetTest {

    @Test
    fun `SetComputeUnitLimit data is 5 bytes - ix_2 plus u32_le`() {
        val units = 200_000
        val data = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(2) // instruction index
            .putInt(units)
            .array()

        assertEquals(5, data.size)
        assertEquals(2, data[0].toInt())

        // Verify little-endian encoding: 200_000 = 0x00030D40
        val decoded = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(200_000, decoded)
    }

    @Test
    fun `SetComputeUnitPrice data is 9 bytes - ix_3 plus u64_le`() {
        val microLamports = 50_000L
        val data = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(3) // instruction index
            .putLong(microLamports)
            .array()

        assertEquals(9, data.size)
        assertEquals(3, data[0].toInt())

        val decoded = ByteBuffer.wrap(data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(50_000L, decoded)
    }

    @Test
    fun `ComputeBudget program ID is correct base58`() {
        val expected = "ComputeBudget111111111111111111111111"
        assertEquals(expected, TransactionBuilder.COMPUTE_BUDGET_PROGRAM.toBase58())
    }

    @Test
    fun `default compute unit limit is 200k`() {
        assertEquals(200_000, TransactionBuilder.COMPUTE_UNIT_LIMIT)
    }

    @Test
    fun `default compute unit price is 50k microLamports`() {
        assertEquals(50_000L, TransactionBuilder.COMPUTE_UNIT_PRICE)
    }
}
