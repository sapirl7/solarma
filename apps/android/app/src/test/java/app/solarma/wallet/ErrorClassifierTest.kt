package app.solarma.wallet

import app.solarma.wallet.ErrorClassifier.Category
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ErrorClassifier] — error classification logic extracted
 * from TransactionProcessor to enable pure unit testing.
 */
class ErrorClassifierTest {

    // ── On-chain program errors → NON_RETRYABLE ──

    @Test
    fun `InvalidAlarmState is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("InvalidAlarmState"))
    }

    @Test
    fun `DeadlinePassed is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("DeadlinePassed"))
    }

    @Test
    fun `TooEarly is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("TooEarly"))
    }

    @Test
    fun `AlreadyInUse is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("AlreadyInUse"))
    }

    @Test
    fun `DepositTooSmall is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("DepositTooSmall"))
    }

    @Test
    fun `AlarmTimeInPast is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("AlarmTimeInPast"))
    }

    @Test
    fun `InvalidDeadline is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("InvalidDeadline"))
    }

    @Test
    fun `PenaltyDestinationRequired is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("PenaltyDestinationRequired"))
    }

    @Test
    fun `InsufficientFunds is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("InsufficientFunds"))
    }

    @Test
    fun `AccountNotFound is non-retryable`() {
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("AccountNotFound"))
    }

    // ── Network/node errors → RETRYABLE ──

    @Test
    fun `timeout is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Connection timeout after 30s"))
    }

    @Test
    fun `timeout is case-insensitive`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("TIMEOUT"))
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Timeout"))
    }

    @Test
    fun `node is behind is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Node is behind by 50 slots"))
    }

    @Test
    fun `blockhash not found is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Blockhash not found"))
    }

    @Test
    fun `429 rate limit is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("HTTP 429 Too Many Requests"))
    }

    @Test
    fun `503 service unavailable is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("HTTP 503"))
    }

    @Test
    fun `502 bad gateway is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("502 Bad Gateway"))
    }

    @Test
    fun `connection refused is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Connection refused"))
    }

    @Test
    fun `ECONNRESET is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("read ECONNRESET"))
    }

    // ── Edge cases ──

    @Test
    fun `null error is retryable`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify(null))
    }

    @Test
    fun `empty string is retryable (default)`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify(""))
    }

    @Test
    fun `unknown error is retryable (default)`() {
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify("Something went wrong"))
    }

    // ── Composite messages ──

    @Test
    fun `error embedded in longer message is classified`() {
        val msg = "Transaction failed: custom program error: InvalidAlarmState at instruction 0"
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify(msg))
    }

    @Test
    fun `DeadlinePassed in JSON error is classified`() {
        val msg = """{"InstructionError":[0,{"Custom":"DeadlinePassed"}]}"""
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify(msg))
    }

    @Test
    fun `timeout in verbose message is classified`() {
        val msg = "Failed to send transaction: request to https://api.devnet.solana.com timeout after 30000ms"
        assertEquals(Category.RETRYABLE, ErrorClassifier.classify(msg))
    }

    // ── All categories are valid enum values ──

    @Test
    fun `Category enum has exactly 2 values`() {
        assertEquals(2, Category.values().size)
    }
}
