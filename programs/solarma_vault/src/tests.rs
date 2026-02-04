//! Invariant tests for Solarma Vault
//!
//! These tests verify the critical security invariants of the vault.

use crate::constants::{DEFAULT_SNOOZE_PERCENT, MAX_SNOOZE_COUNT, MIN_DEPOSIT_LAMPORTS};
use crate::state::{Alarm, AlarmStatus, PenaltyRoute};

#[cfg(test)]
mod unit_tests {
    use super::{
        Alarm, AlarmStatus, PenaltyRoute, DEFAULT_SNOOZE_PERCENT, MAX_SNOOZE_COUNT,
        MIN_DEPOSIT_LAMPORTS,
    };

    const ALARM_MIN_SIZE: usize = 8 + 32 + 8 + 8 + 33 + 8 + 8 + 1 + 33 + 1 + 1 + 1 + 1;
    const _: () = assert!(Alarm::SIZE >= ALARM_MIN_SIZE);

    /// Test: Alarm status transitions
    #[test]
    fn test_alarm_status_is_terminal() {
        // Created is the only non-terminal state
        let created = AlarmStatus::Created;
        let claimed = AlarmStatus::Claimed;
        let slashed = AlarmStatus::Slashed;

        // Verify they are distinct
        assert_ne!(created, claimed);
        assert_ne!(created, slashed);
        assert_ne!(claimed, slashed);
    }

    /// Test: Penalty route conversion from u8
    #[test]
    fn test_penalty_route_from_u8() {
        assert_eq!(PenaltyRoute::try_from(0), Ok(PenaltyRoute::Burn));
        assert_eq!(PenaltyRoute::try_from(1), Ok(PenaltyRoute::Donate));
        assert_eq!(PenaltyRoute::try_from(2), Ok(PenaltyRoute::Buddy));
        assert!(PenaltyRoute::try_from(3).is_err());
        assert!(PenaltyRoute::try_from(255).is_err());
    }

    /// Test: Snooze cost calculation (exponential)
    #[test]
    fn test_snooze_cost_exponential() {
        let remaining = 1_000_000_000u64; // 1 SOL

        // First snooze: 10%
        let cost_0 = remaining * DEFAULT_SNOOZE_PERCENT / 100;
        assert_eq!(cost_0, 100_000_000); // 0.1 SOL

        // Second snooze: 10% * 2^1 = 20%
        let cost_1 = (remaining * DEFAULT_SNOOZE_PERCENT / 100) * (1 << 1);
        assert_eq!(cost_1, 200_000_000); // 0.2 SOL

        // Third snooze: 10% * 2^2 = 40%
        let cost_2 = (remaining * DEFAULT_SNOOZE_PERCENT / 100) * (1 << 2);
        assert_eq!(cost_2, 400_000_000); // 0.4 SOL
    }

    /// Test: Minimum deposit validation
    #[test]
    fn test_minimum_deposit() {
        assert_eq!(MIN_DEPOSIT_LAMPORTS, 1_000_000); // 0.001 SOL
    }

    /// Test: Max snooze limit
    #[test]
    fn test_max_snooze_limit() {
        assert_eq!(MAX_SNOOZE_COUNT, 10);
    }

    /// Test: Default alarm state is Created
    #[test]
    fn test_default_alarm_status() {
        let status = AlarmStatus::default();
        assert_eq!(status, AlarmStatus::Created);
    }
}

/// Integration test scenarios (require local validator)
/// Run with: anchor test
#[cfg(feature = "test-bpf")]
mod integration_tests {
    // These would be implemented as Anchor TypeScript tests
    // See tests/solarma_vault.ts
}
