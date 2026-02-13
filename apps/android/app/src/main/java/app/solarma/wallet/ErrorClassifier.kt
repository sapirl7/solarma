package app.solarma.wallet

/**
 * Classifies transaction errors into retryable vs non-retryable categories.
 *
 * On-chain program errors (InvalidAlarmState, DeadlinePassed, etc.) will never
 * succeed on retry — they should fail immediately. Network/node errors are
 * transient and worth retrying with backoff.
 */
object ErrorClassifier {

    enum class Category {
        /** Transient error — retry with exponential backoff */
        RETRYABLE,
        /** Permanent error — fail immediately, don't waste retries */
        NON_RETRYABLE
    }

    /**
     * Classify an error message from a failed transaction.
     * Returns [Category.RETRYABLE] by default (safe fallback).
     */
    fun classify(error: String?): Category {
        if (error == null) return Category.RETRYABLE

        return when {
            // ── On-chain program errors (permanent) ──
            error.contains("InvalidAlarmState")     -> Category.NON_RETRYABLE
            error.contains("DeadlinePassed")        -> Category.NON_RETRYABLE
            error.contains("TooEarly")              -> Category.NON_RETRYABLE
            error.contains("AlreadyInUse")          -> Category.NON_RETRYABLE
            error.contains("DepositTooSmall")       -> Category.NON_RETRYABLE
            error.contains("AlarmTimeInPast")       -> Category.NON_RETRYABLE
            error.contains("InvalidDeadline")       -> Category.NON_RETRYABLE
            error.contains("PenaltyDestinationRequired") -> Category.NON_RETRYABLE
            error.contains("InsufficientFunds")     -> Category.NON_RETRYABLE
            error.contains("AccountNotFound")       -> Category.NON_RETRYABLE

            // ── Network/node errors (transient) ──
            error.contains("timeout", ignoreCase = true)            -> Category.RETRYABLE
            error.contains("node is behind", ignoreCase = true)     -> Category.RETRYABLE
            error.contains("blockhash not found", ignoreCase = true) -> Category.RETRYABLE
            error.contains("429")                                   -> Category.RETRYABLE
            error.contains("503")                                   -> Category.RETRYABLE
            error.contains("502")                                   -> Category.RETRYABLE
            error.contains("connection refused", ignoreCase = true) -> Category.RETRYABLE
            error.contains("ECONNRESET", ignoreCase = true)        -> Category.RETRYABLE

            // Default: retry (safe fallback for unknown errors)
            else -> Category.RETRYABLE
        }
    }
}
