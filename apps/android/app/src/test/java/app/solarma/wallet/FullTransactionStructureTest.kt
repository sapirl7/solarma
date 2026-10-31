package app.solarma.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey

/**
 * Parses complete transactions exactly as sent (compute budget + program
 * instruction) and checks the wire format. A malformed account key (e.g. a
 * truncated program ID) shifts every following byte and fails these checks;
 * the golden snapshots alone don't include compute budget instructions.
 */
class FullTransactionStructureTest {
    private val instructionBuilder = SolarmaInstructionBuilder()
    private val txBuilder = TransactionBuilder(SolanaRpcClient(), instructionBuilder)
    private val owner = PublicKey(ByteArray(32) { 9 })
    private val blockhash = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR"

    private class Parsed(
        val keys: List<ByteArray>,
        val programIndices: List<Int>,
    )

    /** Minimal legacy-transaction parser (all counts here are < 128, so compact-u16 is 1 byte). */
    private fun parse(tx: ByteArray): Parsed {
        var i = 0
        val sigCount = tx[i++].toInt()
        i += 64 * sigCount
        i += 3 // header
        val keyCount = tx[i++].toInt()
        val keys = (0 until keyCount).map { tx.copyOfRange(i + 32 * it, i + 32 * it + 32) }
        i += 32 * keyCount
        i += 32 // recent blockhash
        val ixCount = tx[i++].toInt()
        val programIndices = mutableListOf<Int>()
        repeat(ixCount) {
            val programIndex = tx[i++].toInt()
            programIndices += programIndex
            val accCount = tx[i++].toInt()
            repeat(accCount) { assertTrue("account index in range", tx[i++].toInt() in 0 until keyCount) }
            val dataLen = tx[i++].toInt()
            i += dataLen
        }
        assertEquals("transaction fully consumed", tx.size, i)
        return Parsed(keys, programIndices)
    }

    @Test
    fun `claim transaction with compute budget is well-formed`() {
        val pda = instructionBuilder.deriveAlarmPda(owner, 42L).address
        val ix = instructionBuilder.buildClaim(owner = owner, alarmPda = pda)
        val tx = txBuilder.buildUnsignedTransactionForSnapshot(owner, ix, blockhash, computeUnitPrice = 50_000L)

        val parsed = parse(tx)
        assertEquals(3, parsed.programIndices.size)
        val computeBudget = TransactionBuilder.COMPUTE_BUDGET_PROGRAM.bytes()
        assertTrue("compute budget program present", parsed.keys.any { it.contentEquals(computeBudget) })
        assertTrue(
            "first two instructions target the compute budget program",
            parsed.programIndices.take(2).all { parsed.keys[it].contentEquals(computeBudget) },
        )
        assertTrue(
            "last instruction targets the Solarma program",
            parsed.keys[parsed.programIndices[2]].contentEquals(SolarmaInstructionBuilder.PROGRAM_ID.bytes()),
        )
    }

    @Test
    fun `dynamic priority fee is clamped`() {
        assertEquals(TransactionBuilder.COMPUTE_UNIT_PRICE_MIN, TransactionBuilder.clampComputeUnitPrice(0))
        assertEquals(123_456L, TransactionBuilder.clampComputeUnitPrice(123_456L))
        assertEquals(TransactionBuilder.COMPUTE_UNIT_PRICE_MAX, TransactionBuilder.clampComputeUnitPrice(Long.MAX_VALUE))
    }
}
