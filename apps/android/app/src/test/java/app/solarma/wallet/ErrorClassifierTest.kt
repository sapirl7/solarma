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
    fun `TooEarly is retry-later (device clock ahead of chain)`() {
        assertEquals(Category.RETRY_LATER, ErrorClassifier.classify("TooEarly"))
    }

    @Test
    fun `DeadlineNotPassed and BuddyOnlyWindow are retry-later`() {
        assertEquals(Category.RETRY_LATER, ErrorClassifier.classify("DeadlineNotPassed"))
        assertEquals(Category.RETRY_LATER, ErrorClassifier.classify("BuddyOnlyWindow"))
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
    fun `Category enum has exactly 3 values`() {
        assertEquals(3, Category.values().size)
    }

    // ── Numeric program error codes (what RPCs actually return) ──

    @Test
    fun `hex code from simulation is parsed`() {
        val msg = "Transaction simulation failed: Error processing Instruction 2: custom program error: 0x1772"
        assertEquals(6002, ErrorClassifier.programErrorCode(msg))
        assertEquals("InvalidAlarmState", ErrorClassifier.solarmaErrorName(6002))
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify(msg))
    }

    @Test
    fun `JSON code from signature status is parsed`() {
        val err = """{"InstructionError":[2,{"Custom":6000}]}"""
        assertEquals(6000, ErrorClassifier.programErrorCode(err))
        assertEquals("DeadlinePassed", ErrorClassifier.solarmaErrorName(6000))
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify(err))
    }

    @Test
    fun `time-dependent codes are retry-later`() {
        // 6001 DeadlineNotPassed, 6014 TooEarly, 6016 BuddyOnlyWindow
        for (code in listOf(6001, 6014, 6016)) {
            val err = """{"InstructionError":[2,{"Custom":$code}]}"""
            assertEquals("code $code", Category.RETRY_LATER, ErrorClassifier.classify(err))
        }
    }

    @Test
    fun `newest error codes map to the right names`() {
        assertEquals("DeadlineTooFar", ErrorClassifier.solarmaErrorName(6017))
        assertEquals("InvalidPenaltyDestination", ErrorClassifier.solarmaErrorName(6018))
        assertNull(ErrorClassifier.solarmaErrorName(6019))
    }

    @Test
    fun `system program custom error is non-retryable`() {
        // e.g. 0x0 = account already in use when the alarm PDA exists
        assertEquals(Category.NON_RETRYABLE, ErrorClassifier.classify("custom program error: 0x0"))
    }

    @Test
    fun `message without a code has no code`() {
        assertNull(ErrorClassifier.programErrorCode("timeout after 30000ms"))
        assertNull(ErrorClassifier.programErrorCode(null))
    }
}
