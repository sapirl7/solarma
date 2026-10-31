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
        NON_RETRYABLE,

        /** Valid later (e.g. slash before the deadline): retry without consuming a retry attempt */
        RETRY_LATER,
    }

    /** SolarmaError variants in declaration order; Anchor numbers them from 6000. */
    private val SOLARMA_ERRORS =
        listOf(
            "DeadlinePassed",
            "DeadlineNotPassed",
            "InvalidAlarmState",
            "InvalidPenaltyRoute",
            "InsufficientDeposit",
            "Overflow",
            "AlarmTimeInPast",
            "InvalidDeadline",
            "DepositTooSmall",
            "PenaltyDestinationRequired",
            "InvalidSinkAddress",
            "MaxSnoozesReached",
            "InvalidPenaltyRecipient",
            "PenaltyDestinationNotSet",
            "TooEarly",
            "TooLateForRefund",
            "BuddyOnlyWindow",
            "DeadlineTooFar",
            "InvalidPenaltyDestination",
            "ProgramPaused",
            "DepositTooLarge",
            "Unauthorized",
        )

    private const val ANCHOR_CUSTOM_ERROR_OFFSET = 6000

    /** Errors that only mean "not yet": the same transaction can succeed later. */
    private val TIME_DEPENDENT = setOf("DeadlineNotPassed", "TooEarly", "BuddyOnlyWindow")

    private val HEX_CODE = Regex("custom program error: 0x([0-9a-fA-F]+)")
    private val JSON_CODE = Regex("\"Custom\"\\s*:\\s*(\\d+)")

    /**
     * Extract a numeric custom program error code from an RPC error, e.g.
     * `custom program error: 0x1772` (simulation) or `{"InstructionError":[2,{"Custom":6002}]}`
     * (signature status). Returns null if there is none.
     */
    fun programErrorCode(error: String?): Int? {
        if (error == null) return null
        HEX_CODE.find(error)?.let { return it.groupValues[1].toIntOrNull(16) }
        JSON_CODE.find(error)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** Name of a SolarmaError code, or null for non-Solarma codes. */
    fun solarmaErrorName(code: Int): String? = SOLARMA_ERRORS.getOrNull(code - ANCHOR_CUSTOM_ERROR_OFFSET)

    /** A user-facing explanation for errors the user can act on, or null. */
    fun userMessage(error: String?): String? {
        val name = programErrorCode(error)?.let { solarmaErrorName(it) } ?: SOLARMA_ERRORS.firstOrNull { error?.contains(it) == true }
        return when (name) {
            "DepositTooLarge" -> "This deposit is above the current maximum. Try a smaller amount."
            "ProgramPaused" -> "Solarma is temporarily not accepting new deposits. Try again later."
            "AlarmTimeInPast" -> "The alarm time has already passed. Pick a later time."
            "DeadlineTooFar" -> "Alarms with a deposit can be set at most 30 days ahead."
            "InvalidPenaltyDestination" -> "The buddy address can't be your own wallet."
            else -> null
        }
    }

    /**
     * Classify an error message from a failed transaction.
     * Returns [Category.RETRYABLE] by default (safe fallback).
     */
    fun classify(error: String?): Category {
        if (error == null) return Category.RETRYABLE

        // Numeric program errors are deterministic for the current on-chain state.
        val code = programErrorCode(error)
        if (code != null) {
            val name = solarmaErrorName(code)
            return if (name != null && name in TIME_DEPENDENT) Category.RETRY_LATER else Category.NON_RETRYABLE
        }

        return when {
            // ── Not yet valid (time windows) ──
            TIME_DEPENDENT.any { error.contains(it) } -> Category.RETRY_LATER

            // ── On-chain program errors (permanent) ──
            error.contains("InvalidAlarmState") -> Category.NON_RETRYABLE
            error.contains("DeadlinePassed") -> Category.NON_RETRYABLE
            error.contains("AlreadyInUse") -> Category.NON_RETRYABLE
            error.contains("DepositTooSmall") -> Category.NON_RETRYABLE
            error.contains("AlarmTimeInPast") -> Category.NON_RETRYABLE
            error.contains("InvalidDeadline") -> Category.NON_RETRYABLE
            error.contains("DeadlineTooFar") -> Category.NON_RETRYABLE
            error.contains("PenaltyDestinationRequired") -> Category.NON_RETRYABLE
            error.contains("InvalidPenaltyDestination") -> Category.NON_RETRYABLE
            error.contains("ProgramPaused") -> Category.NON_RETRYABLE
            error.contains("DepositTooLarge") -> Category.NON_RETRYABLE
            error.contains("Unauthorized") -> Category.NON_RETRYABLE
            error.contains("InvalidPenaltyRecipient") -> Category.NON_RETRYABLE
            error.contains("MaxSnoozesReached") -> Category.NON_RETRYABLE
            error.contains("InsufficientDeposit") -> Category.NON_RETRYABLE
            error.contains("TooLateForRefund") -> Category.NON_RETRYABLE
            error.contains("AccountNotInitialized") -> Category.NON_RETRYABLE
            error.contains("InsufficientFunds") -> Category.NON_RETRYABLE
            error.contains("AccountNotFound") -> Category.NON_RETRYABLE

            // ── Network/node errors (transient) ──
            error.contains("timeout", ignoreCase = true) -> Category.RETRYABLE
            error.contains("node is behind", ignoreCase = true) -> Category.RETRYABLE
            error.contains("blockhash not found", ignoreCase = true) -> Category.RETRYABLE
            error.contains("429") -> Category.RETRYABLE
            error.contains("503") -> Category.RETRYABLE
            error.contains("502") -> Category.RETRYABLE
            error.contains("connection refused", ignoreCase = true) -> Category.RETRYABLE
            error.contains("ECONNRESET", ignoreCase = true) -> Category.RETRYABLE

            // Default: retry (safe fallback for unknown errors)
            else -> Category.RETRYABLE
        }
    }
}
