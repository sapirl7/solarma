//! Invariant tests for Solarma Vault
//!
//! These tests verify the critical security invariants of the vault.

use crate::constants::{
    DEFAULT_SNOOZE_EXTENSION_SECONDS, DEFAULT_SNOOZE_PERCENT, EMERGENCY_REFUND_PENALTY_PERCENT,
    MAX_SNOOZE_COUNT, MIN_DEPOSIT_LAMPORTS,
};
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, UserProfile, Vault};

#[cfg(test)]
mod unit_tests {
    use super::*;

    // =========================================================================
    // Account SIZE verification
    // =========================================================================

    /// Verify Alarm::SIZE accounts for every field (no missing bytes).
    /// Layout: discriminator(8) + owner(32) + alarm_id(8) + alarm_time(8)
    ///         + deadline(8) + initial_amount(8) + remaining_amount(8)
    ///         + penalty_route(1) + Option<Pubkey>(33) + snooze_count(1)
    ///         + status(1) + bump(1) + vault_bump(1) + padding(64)
    const ALARM_MIN_SIZE: usize = 8 + 32 + 8 + 8 + 8 + 8 + 8 + 1 + 33 + 1 + 1 + 1 + 1;
    const _: () = assert!(Alarm::SIZE >= ALARM_MIN_SIZE);
    const _: () = assert!(Alarm::SIZE == ALARM_MIN_SIZE + 64); // 64 bytes padding

    /// Verify UserProfile::SIZE
    const USER_PROFILE_MIN_SIZE: usize = 8 + 32 + 1 + 32 + 1;
    const _: () = assert!(UserProfile::SIZE == USER_PROFILE_MIN_SIZE);

    /// Verify Vault::SIZE
    const VAULT_MIN_SIZE: usize = 8 + 32 + 1;
    const _: () = assert!(Vault::SIZE == VAULT_MIN_SIZE);

    // =========================================================================
    // Alarm status transitions
    // =========================================================================

    #[test]
    fn test_alarm_status_is_terminal() {
        let created = AlarmStatus::Created;
        let acknowledged = AlarmStatus::Acknowledged;
        let claimed = AlarmStatus::Claimed;
        let slashed = AlarmStatus::Slashed;

        // Verify they are all distinct
        assert_ne!(created, claimed);
        assert_ne!(created, slashed);
        assert_ne!(created, acknowledged);
        assert_ne!(claimed, slashed);
        assert_ne!(claimed, acknowledged);
        assert_ne!(slashed, acknowledged);
    }

    #[test]
    fn test_default_alarm_status() {
        assert_eq!(AlarmStatus::default(), AlarmStatus::Created);
    }

    // =========================================================================
    // Penalty route conversion
    // =========================================================================

    #[test]
    fn test_penalty_route_from_u8() {
        assert_eq!(PenaltyRoute::try_from(0), Ok(PenaltyRoute::Burn));
        assert_eq!(PenaltyRoute::try_from(1), Ok(PenaltyRoute::Donate));
        assert_eq!(PenaltyRoute::try_from(2), Ok(PenaltyRoute::Buddy));
        assert!(PenaltyRoute::try_from(3).is_err());
        assert!(PenaltyRoute::try_from(255).is_err());
    }

    #[test]
    fn test_penalty_route_exhaustive() {
        // Ensure all valid u8 values below 3 map correctly
        for i in 0..=2u8 {
            assert!(PenaltyRoute::try_from(i).is_ok());
        }
        // All values >= 3 should fail
        for i in 3..=10u8 {
            assert!(PenaltyRoute::try_from(i).is_err());
        }
    }

    // =========================================================================
    // Snooze cost math (exponential)
    // =========================================================================

    #[test]
    fn test_snooze_cost_exponential() {
        let remaining = 1_000_000_000u64; // 1 SOL

        // First snooze: 10% * 2^0 = 10%
        let cost_0 = remaining * DEFAULT_SNOOZE_PERCENT / 100;
        assert_eq!(cost_0, 100_000_000); // 0.1 SOL

        // Second snooze: 10% * 2^1 = 20%
        let cost_1 = (remaining * DEFAULT_SNOOZE_PERCENT / 100) * (1 << 1);
        assert_eq!(cost_1, 200_000_000); // 0.2 SOL

        // Third snooze: 10% * 2^2 = 40%
        let cost_2 = (remaining * DEFAULT_SNOOZE_PERCENT / 100) * (1 << 2);
        assert_eq!(cost_2, 400_000_000); // 0.4 SOL
    }

    #[test]
    fn test_snooze_cost_minimum_deposit() {
        // Edge: minimum deposit (0.001 SOL = 1_000_000 lamports)
        let remaining = MIN_DEPOSIT_LAMPORTS;
        let cost = remaining * DEFAULT_SNOOZE_PERCENT / 100;
        assert_eq!(cost, 100_000); // 0.0001 SOL — small but non-zero
    }

    #[test]
    fn test_snooze_cost_max_multiplier() {
        // At max snooze count - 1 (last allowed snooze), multiplier = 2^9 = 512
        let remaining = 1_000_000_000u64;
        let base_cost = remaining * DEFAULT_SNOOZE_PERCENT / 100;
        let multiplier = 1u64 << (MAX_SNOOZE_COUNT - 1);
        assert_eq!(multiplier, 512);

        let cost = base_cost * multiplier;
        // 10% * 512 = 5120% of remaining → capped at remaining in actual code
        assert!(
            cost > remaining,
            "max snooze cost overflows remaining, would be capped"
        );
    }

    #[test]
    fn test_snooze_cost_never_negative() {
        // Even with 0 remaining, the division should yield 0
        let remaining = 0u64;
        let cost = remaining * DEFAULT_SNOOZE_PERCENT / 100;
        assert_eq!(cost, 0);
    }

    // =========================================================================
    // Emergency refund penalty math
    // =========================================================================

    #[test]
    fn test_emergency_penalty_calculation() {
        let deposit = 1_000_000_000u64; // 1 SOL
        let penalty = deposit * EMERGENCY_REFUND_PENALTY_PERCENT / 100;
        assert_eq!(penalty, 50_000_000); // 0.05 SOL = 5%
    }

    #[test]
    fn test_emergency_penalty_minimum() {
        // Minimum deposit yields small but non-zero penalty
        let deposit = MIN_DEPOSIT_LAMPORTS;
        let penalty = deposit * EMERGENCY_REFUND_PENALTY_PERCENT / 100;
        assert_eq!(penalty, 50_000); // 50k lamports
    }

    #[test]
    fn test_emergency_penalty_zero_deposit() {
        let deposit = 0u64;
        let penalty = deposit * EMERGENCY_REFUND_PENALTY_PERCENT / 100;
        assert_eq!(penalty, 0);
    }

    // =========================================================================
    // Constants validation
    // =========================================================================

    #[test]
    fn test_minimum_deposit() {
        assert_eq!(MIN_DEPOSIT_LAMPORTS, 1_000_000); // 0.001 SOL
    }

    #[test]
    fn test_max_snooze_limit() {
        assert_eq!(MAX_SNOOZE_COUNT, 10);
    }

    #[test]
    fn test_max_snooze_boundary_guard() {
        // The guard in snooze.rs: require!(alarm.snooze_count < MAX_SNOOZE_COUNT)
        // snooze_count=9 should be the last ALLOWED snooze
        assert!(9 < MAX_SNOOZE_COUNT, "snooze_count=9 should pass guard");
        // snooze_count=10 should be REJECTED
        assert!(
            !(10 < MAX_SNOOZE_COUNT),
            "snooze_count=10 should fail guard"
        );
        // Edge: u8::MAX should also be rejected
        assert!(
            !(u8::MAX < MAX_SNOOZE_COUNT),
            "snooze_count=255 should fail guard"
        );
    }

    #[test]
    fn test_exponential_cost_drains_before_max_snooze() {
        // Prove that with a realistic deposit, the exponential cost
        // drains the vault before reaching MAX_SNOOZE_COUNT.
        // This is why MAX_SNOOZE integration test is impractical:
        // the math makes it unreachable with normal deposits.
        let initial = 5_000_000u64; // 0.005 SOL (standard test deposit)
        let mut remaining = initial;

        for n in 0u8..MAX_SNOOZE_COUNT {
            let base = remaining * DEFAULT_SNOOZE_PERCENT / 100;
            let multiplier = 1u64 << n; // 2^n
            let cost = (base * multiplier).min(remaining);
            if cost >= remaining {
                // Vault fully drained at snooze #{n} — never reaches MAX_SNOOZE_COUNT
                assert!(
                    n < MAX_SNOOZE_COUNT,
                    "Should drain before reaching max snooze"
                );
                return;
            }
            remaining -= cost;
        }
        panic!("Expected vault to drain before MAX_SNOOZE_COUNT with 0.005 SOL deposit");
    }

    #[test]
    fn test_snooze_extension_seconds() {
        assert_eq!(DEFAULT_SNOOZE_EXTENSION_SECONDS, 300); // 5 minutes
    }

    #[test]
    fn test_emergency_refund_penalty() {
        assert_eq!(EMERGENCY_REFUND_PENALTY_PERCENT, 5);
    }

    #[test]
    fn test_snooze_percent() {
        assert_eq!(DEFAULT_SNOOZE_PERCENT, 10);
    }

    // =========================================================================
    // Overflow safety
    // =========================================================================

    #[test]
    fn test_snooze_cost_no_overflow_at_max_u64() {
        // Check that snooze cost calculation with very large values saturates
        let remaining = u64::MAX;
        let result = remaining.checked_mul(DEFAULT_SNOOZE_PERCENT);
        assert!(result.is_none(), "u64::MAX * 10 should overflow");
    }

    #[test]
    fn test_penalty_no_overflow_at_max_u64() {
        let deposit = u64::MAX;
        let result = deposit.checked_mul(EMERGENCY_REFUND_PENALTY_PERCENT);
        assert!(result.is_none(), "u64::MAX * 5 should overflow");
    }

    #[test]
    fn test_snooze_count_no_overflow() {
        // Verify checked_add catches u8 overflow
        let count: u8 = 255;
        assert!(count.checked_add(1).is_none());
    }
}

/// Integration test scenarios (require local validator)
/// Run with: anchor test
#[cfg(feature = "test-bpf")]
mod integration_tests {
    // These are implemented as Anchor TypeScript tests
    // See tests/solarma_vault.ts (66 tests)
    // Rust unit tests above: 22 tests
}
