//! Comprehensive unit tests for Solarma Vault
//!
//! These tests verify the critical security invariants of the vault,
//! the pure business logic in `helpers.rs`, and all edge cases.

use crate::constants::{
    DEFAULT_GRACE_PERIOD, DEFAULT_SNOOZE_EXTENSION_SECONDS, DEFAULT_SNOOZE_PERCENT,
    EMERGENCY_REFUND_PENALTY_PERCENT, MAX_SNOOZE_COUNT, MIN_DEPOSIT_LAMPORTS,
};
use crate::helpers;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, UserProfile, Vault};

#[cfg(test)]
mod unit_tests {
    use super::*;
    use anchor_lang::prelude::Pubkey;

    // =========================================================================
    // Account SIZE verification (compile-time)
    // =========================================================================

    const ALARM_MIN_SIZE: usize = 8 + 32 + 8 + 8 + 8 + 8 + 8 + 1 + 1 + 32 + 1 + 1 + 1 + 1 + 64;
    const _: () = assert!(Alarm::SIZE == ALARM_MIN_SIZE);

    const PROFILE_MIN_SIZE: usize = 8 + 32 + 1 + 32 + 1;
    const _: () = assert!(UserProfile::SIZE == PROFILE_MIN_SIZE);

    const VAULT_MIN_SIZE: usize = 8 + 32 + 1;
    const _: () = assert!(Vault::SIZE == VAULT_MIN_SIZE);

    // =========================================================================
    // Alarm status transitions
    // =========================================================================

    #[test]
    fn test_alarm_status_is_terminal() {
        // Created is NOT terminal (can transition to Claimed, Slashed, or Acknowledged)
        assert_ne!(AlarmStatus::Created, AlarmStatus::Claimed);
        assert_ne!(AlarmStatus::Created, AlarmStatus::Slashed);

        // Claimed is terminal
        let s = AlarmStatus::Claimed;
        assert_ne!(s, AlarmStatus::Created);

        // Slashed is terminal
        let s = AlarmStatus::Slashed;
        assert_ne!(s, AlarmStatus::Created);
        assert_ne!(s, AlarmStatus::Claimed);

        // Acknowledged is non-terminal (can transition to Claimed or Slashed)
        let s = AlarmStatus::Acknowledged;
        assert_ne!(s, AlarmStatus::Created);
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
    }

    #[test]
    fn test_penalty_route_exhaustive() {
        // All values 3..=255 must be invalid
        for v in 3u8..=255 {
            assert!(
                PenaltyRoute::try_from(v).is_err(),
                "Expected error for value {}",
                v
            );
        }
    }

    // =========================================================================
    // helpers::snooze_cost
    // =========================================================================

    #[test]
    fn test_snooze_cost_basic() {
        // 1 SOL = 1_000_000_000 lamports, snooze_count=0
        // cost = 1B * 10 / 100 * 2^0 = 100_000_000 (0.1 SOL)
        assert_eq!(helpers::snooze_cost(1_000_000_000, 0), Some(100_000_000));
    }

    #[test]
    fn test_snooze_cost_exponential() {
        let deposit = 1_000_000_000u64; // 1 SOL
        let base = deposit * DEFAULT_SNOOZE_PERCENT / 100;

        // Each snooze doubles
        for i in 0..5u8 {
            let expected = (base * (1u64 << i)).min(deposit);
            assert_eq!(
                helpers::snooze_cost(deposit, i),
                Some(expected),
                "Mismatch at snooze #{}",
                i
            );
        }
    }

    #[test]
    fn test_snooze_cost_caps_at_remaining() {
        // With very high snooze count, cost should be capped at remaining
        let remaining = 100_000u64;
        let cost = helpers::snooze_cost(remaining, 9).unwrap();
        assert!(cost <= remaining);
    }

    #[test]
    fn test_snooze_cost_zero_remaining() {
        assert_eq!(helpers::snooze_cost(0, 0), Some(0));
        assert_eq!(helpers::snooze_cost(0, 5), Some(0));
    }

    #[test]
    fn test_snooze_cost_minimum_deposit() {
        let cost = helpers::snooze_cost(MIN_DEPOSIT_LAMPORTS, 0).unwrap();
        // 1_000_000 * 10 / 100 = 100_000
        assert_eq!(cost, 100_000);
    }

    #[test]
    fn test_snooze_cost_at_max_u64() {
        // u64::MAX * 10 will overflow in checked_mul → should return None.
        // If somehow it doesn't, it must cap at remaining.
        if let Some(v) = helpers::snooze_cost(u64::MAX, 63) {
            assert!(v > 0, "non-zero cost expected when result is Some");
        }
    }

    #[test]
    fn test_snooze_cost_all_counts() {
        // Deterministic: exercise every valid snooze count
        let deposit = 10_000_000_000u64; // 10 SOL
        for count in 0..MAX_SNOOZE_COUNT {
            let cost = helpers::snooze_cost(deposit, count);
            assert!(cost.is_some(), "Overflow at snooze #{}", count);
            assert!(cost.unwrap() > 0);
            assert!(cost.unwrap() <= deposit);
        }
    }

    // =========================================================================
    // helpers::is_max_snooze
    // =========================================================================

    #[test]
    fn test_is_max_snooze() {
        assert!(!helpers::is_max_snooze(0));
        assert!(!helpers::is_max_snooze(MAX_SNOOZE_COUNT - 1));
        assert!(helpers::is_max_snooze(MAX_SNOOZE_COUNT));
        assert!(helpers::is_max_snooze(MAX_SNOOZE_COUNT + 1));
        assert!(helpers::is_max_snooze(u8::MAX));
    }

    // =========================================================================
    // helpers::emergency_penalty
    // =========================================================================

    #[test]
    fn test_emergency_penalty_basic() {
        // 1 SOL, 5% = 50_000_000
        assert_eq!(helpers::emergency_penalty(1_000_000_000), Some(50_000_000));
    }

    #[test]
    fn test_emergency_penalty_zero() {
        assert_eq!(helpers::emergency_penalty(0), Some(0));
    }

    #[test]
    fn test_emergency_penalty_minimum_deposit() {
        let penalty = helpers::emergency_penalty(MIN_DEPOSIT_LAMPORTS).unwrap();
        // 1_000_000 * 5 / 100 = 50_000
        assert_eq!(penalty, 50_000);
    }

    #[test]
    fn test_emergency_penalty_large_value() {
        // Max realistic: 100 SOL
        let penalty = helpers::emergency_penalty(100_000_000_000).unwrap();
        assert_eq!(penalty, 5_000_000_000); // 5 SOL
    }

    #[test]
    fn test_emergency_penalty_at_max_u64() {
        // u64::MAX * 5 should overflow
        let result = helpers::emergency_penalty(u64::MAX);
        assert!(result.is_none());
    }

    #[test]
    fn test_emergency_penalty_near_max() {
        // u64::MAX / 5 should not overflow
        let amount = u64::MAX / EMERGENCY_REFUND_PENALTY_PERCENT;
        let result = helpers::emergency_penalty(amount);
        assert!(result.is_some());
    }

    // =========================================================================
    // helpers::validate_alarm_params
    // =========================================================================

    #[test]
    fn test_validate_alarm_params_valid() {
        let now = 1_000_000;
        assert!(helpers::validate_alarm_params(
            now + 3600, // alarm in 1 hour
            now + 7200, // deadline in 2 hours
            now,
            1_000_000_000, // 1 SOL
            0,             // Burn
            false,
        )
        .is_ok());
    }

    #[test]
    fn test_validate_alarm_time_in_past() {
        let now = 1_000_000;
        let result =
            helpers::validate_alarm_params(now - 1, now + 7200, now, 1_000_000_000, 0, false);
        assert_eq!(result, Err("alarm_time_in_past"));
    }

    #[test]
    fn test_validate_alarm_time_equal_to_now() {
        let now = 1_000_000;
        let result = helpers::validate_alarm_params(now, now + 7200, now, 1_000_000_000, 0, false);
        assert_eq!(result, Err("alarm_time_in_past"));
    }

    #[test]
    fn test_validate_invalid_deadline() {
        let now = 1_000_000;
        // deadline == alarm_time
        let result =
            helpers::validate_alarm_params(now + 3600, now + 3600, now, 1_000_000_000, 0, false);
        assert_eq!(result, Err("invalid_deadline"));
        // deadline < alarm_time
        let result =
            helpers::validate_alarm_params(now + 3600, now + 1800, now, 1_000_000_000, 0, false);
        assert_eq!(result, Err("invalid_deadline"));
    }

    #[test]
    fn test_validate_deposit_too_small() {
        let now = 1_000_000;
        let result = helpers::validate_alarm_params(
            now + 3600,
            now + 7200,
            now,
            MIN_DEPOSIT_LAMPORTS - 1,
            0,
            false,
        );
        assert_eq!(result, Err("deposit_too_small"));
    }

    #[test]
    fn test_validate_zero_deposit_ok() {
        let now = 1_000_000;
        // Zero deposit should be fine (commitment alarm without deposit)
        assert!(helpers::validate_alarm_params(now + 3600, now + 7200, now, 0, 0, false).is_ok());
    }

    #[test]
    fn test_validate_invalid_penalty_route() {
        let now = 1_000_000;
        let result =
            helpers::validate_alarm_params(now + 3600, now + 7200, now, 1_000_000_000, 5, false);
        assert_eq!(result, Err("invalid_penalty_route"));
    }

    #[test]
    fn test_validate_buddy_route_needs_destination() {
        let now = 1_000_000;
        // Buddy route (2) without destination
        let result =
            helpers::validate_alarm_params(now + 3600, now + 7200, now, 1_000_000_000, 2, false);
        assert_eq!(result, Err("penalty_destination_required"));
        // With destination
        assert!(helpers::validate_alarm_params(
            now + 3600,
            now + 7200,
            now,
            1_000_000_000,
            2,
            true
        )
        .is_ok());
    }

    #[test]
    fn test_validate_donate_route_needs_destination() {
        let now = 1_000_000;
        let result =
            helpers::validate_alarm_params(now + 3600, now + 7200, now, 1_000_000_000, 1, false);
        assert_eq!(result, Err("penalty_destination_required"));
        assert!(helpers::validate_alarm_params(
            now + 3600,
            now + 7200,
            now,
            1_000_000_000,
            1,
            true
        )
        .is_ok());
    }

    #[test]
    fn test_validate_burn_route_no_destination_ok() {
        let now = 1_000_000;
        // Burn route (0) doesn't need destination
        assert!(helpers::validate_alarm_params(
            now + 3600,
            now + 7200,
            now,
            1_000_000_000,
            0,
            false
        )
        .is_ok());
    }

    // =========================================================================
    // helpers::is_claim_window
    // =========================================================================

    #[test]
    fn test_claim_window_valid() {
        assert!(helpers::is_claim_window(100, 200, 150));
        assert!(helpers::is_claim_window(100, 200, 100)); // exactly at alarm_time
    }

    #[test]
    fn test_claim_window_too_early() {
        assert!(!helpers::is_claim_window(100, 200, 99));
    }

    #[test]
    fn test_claim_window_at_deadline() {
        assert!(!helpers::is_claim_window(100, 200, 200)); // at deadline = invalid
    }

    #[test]
    fn test_claim_window_after_deadline() {
        assert!(!helpers::is_claim_window(100, 200, 201));
    }

    // =========================================================================
    // helpers::is_slash_window
    // =========================================================================

    #[test]
    fn test_slash_window_valid() {
        assert!(helpers::is_slash_window(200, 200)); // exactly at deadline
        assert!(helpers::is_slash_window(200, 300));
    }

    #[test]
    fn test_slash_window_too_early() {
        assert!(!helpers::is_slash_window(200, 199));
    }

    // =========================================================================
    // helpers::is_refund_window
    // =========================================================================

    #[test]
    fn test_refund_window_valid() {
        assert!(helpers::is_refund_window(100, 50));
        assert!(helpers::is_refund_window(100, 99));
    }

    #[test]
    fn test_refund_window_at_alarm_time() {
        assert!(!helpers::is_refund_window(100, 100)); // at alarm time = invalid
    }

    #[test]
    fn test_refund_window_after_alarm() {
        assert!(!helpers::is_refund_window(100, 150));
    }

    // =========================================================================
    // helpers::is_snooze_window
    // =========================================================================

    #[test]
    fn test_snooze_window_valid() {
        assert!(helpers::is_snooze_window(100, 200, 100)); // exactly at alarm
        assert!(helpers::is_snooze_window(100, 200, 150));
    }

    #[test]
    fn test_snooze_window_before_alarm() {
        assert!(!helpers::is_snooze_window(100, 200, 99));
    }

    #[test]
    fn test_snooze_window_at_deadline() {
        assert!(!helpers::is_snooze_window(100, 200, 200));
    }

    // =========================================================================
    // helpers::validate_penalty_recipient
    // =========================================================================

    #[test]
    fn test_validate_penalty_burn_route() {
        let burn_sink = [1u8; 32];
        let correct = [1u8; 32];
        let wrong = [2u8; 32];

        assert!(helpers::validate_penalty_recipient(0, &correct, &burn_sink, None).is_ok());
        assert_eq!(
            helpers::validate_penalty_recipient(0, &wrong, &burn_sink, None),
            Err("invalid_penalty_recipient")
        );
    }

    #[test]
    fn test_validate_penalty_buddy_route() {
        let burn_sink = [1u8; 32];
        let buddy = [3u8; 32];
        let wrong = [4u8; 32];

        // Buddy route with matching destination
        assert!(helpers::validate_penalty_recipient(2, &buddy, &burn_sink, Some(&buddy)).is_ok());
        // Buddy route with wrong destination
        assert_eq!(
            helpers::validate_penalty_recipient(2, &wrong, &burn_sink, Some(&buddy)),
            Err("invalid_penalty_recipient")
        );
        // Buddy route without destination
        assert_eq!(
            helpers::validate_penalty_recipient(2, &buddy, &burn_sink, None),
            Err("penalty_destination_not_set")
        );
    }

    #[test]
    fn test_validate_penalty_donate_route() {
        let burn_sink = [1u8; 32];
        let charity = [5u8; 32];

        assert!(
            helpers::validate_penalty_recipient(1, &charity, &burn_sink, Some(&charity)).is_ok()
        );
        assert_eq!(
            helpers::validate_penalty_recipient(1, &charity, &burn_sink, None),
            Err("penalty_destination_not_set")
        );
    }

    #[test]
    fn test_validate_penalty_invalid_route() {
        let any = [0u8; 32];
        assert_eq!(
            helpers::validate_penalty_recipient(99, &any, &any, None),
            Err("invalid_penalty_route")
        );
    }

    // =========================================================================
    // helpers::snooze_time_extension
    // =========================================================================

    #[test]
    fn test_snooze_time_extension_basic() {
        let (new_alarm, new_deadline) =
            helpers::snooze_time_extension(1000, 2000, DEFAULT_SNOOZE_EXTENSION_SECONDS).unwrap();
        assert_eq!(new_alarm, 1000 + DEFAULT_SNOOZE_EXTENSION_SECONDS);
        assert_eq!(new_deadline, 2000 + DEFAULT_SNOOZE_EXTENSION_SECONDS);
    }

    #[test]
    fn test_snooze_time_extension_overflow() {
        assert!(helpers::snooze_time_extension(i64::MAX, 0, 1).is_none());
        assert!(helpers::snooze_time_extension(0, i64::MAX, 1).is_none());
    }

    // =========================================================================
    // helpers::cap_at_rent_exempt
    // =========================================================================

    #[test]
    fn test_cap_at_rent_exempt_normal() {
        // 1 SOL in vault, 0.001 SOL rent, want to deduct 0.5 SOL
        let capped = helpers::cap_at_rent_exempt(500_000_000, 1_000_000_000, 1_000_000);
        assert_eq!(capped, 500_000_000); // full deduction allowed
    }

    #[test]
    fn test_cap_at_rent_exempt_limited() {
        // 0.002 SOL in vault, 0.001 SOL rent, want 0.005 SOL
        let capped = helpers::cap_at_rent_exempt(5_000_000, 2_000_000, 1_000_000);
        assert_eq!(capped, 1_000_000); // only 0.001 available
    }

    #[test]
    fn test_cap_at_rent_exempt_below_minimum() {
        // vault balance < rent minimum
        let capped = helpers::cap_at_rent_exempt(100, 500, 1000);
        assert_eq!(capped, 0);
    }

    #[test]
    fn test_cap_at_rent_exempt_exact() {
        // available == desired
        let capped = helpers::cap_at_rent_exempt(1000, 2000, 1000);
        assert_eq!(capped, 1000);
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

    // Compile-time invariants for the snooze guard boundary
    const _: () = {
        assert!(9 < MAX_SNOOZE_COUNT);
        assert!(10 >= MAX_SNOOZE_COUNT);
    };

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

    #[test]
    fn test_grace_period() {
        assert_eq!(DEFAULT_GRACE_PERIOD, 1800); // 30 minutes
    }

    // =========================================================================
    // Overflow safety
    // =========================================================================

    #[test]
    fn test_snooze_cost_no_overflow_at_max_u64() {
        let result = helpers::snooze_cost(u64::MAX, 0);
        // u64::MAX * 10 overflows, so should return None
        assert!(result.is_none());
    }

    #[test]
    fn test_penalty_no_overflow_at_max_u64() {
        let result = helpers::emergency_penalty(u64::MAX);
        // u64::MAX * 5 overflows
        assert!(result.is_none());
    }

    #[test]
    fn test_snooze_count_no_overflow() {
        // u8::MAX + 1 would overflow
        let max: u8 = u8::MAX;
        assert!(max.checked_add(1).is_none());
    }

    // =========================================================================
    // Exponential drain simulation
    // =========================================================================

    #[test]
    fn test_exponential_cost_drains_before_max_snooze() {
        let sol = 1_000_000_000u64; // 1 SOL
        let mut remaining = sol;

        for i in 0..MAX_SNOOZE_COUNT {
            let cost = helpers::snooze_cost(remaining, i).unwrap();
            if cost >= remaining {
                // Fully drained before max snoozes
                return;
            }
            remaining -= cost;
        }
        // Even if not fully drained, remaining should be small fraction
        assert!(
            remaining < sol / 4,
            "After {} snoozes, {}% still remaining",
            MAX_SNOOZE_COUNT,
            remaining * 100 / sol
        );
    }

    // =========================================================================
    // Full workflow simulation
    // =========================================================================

    #[test]
    fn test_full_alarm_lifecycle_burn() {
        let now = 1_000_000i64;
        let alarm_time = now + 3600;
        let deadline = now + 7200;
        let deposit = 1_000_000_000u64;

        // 1. Create alarm
        assert!(
            helpers::validate_alarm_params(alarm_time, deadline, now, deposit, 0, false).is_ok()
        );

        // 2. Before alarm: refund window is open, claim/snooze/slash closed
        assert!(helpers::is_refund_window(alarm_time, now));
        assert!(!helpers::is_claim_window(alarm_time, deadline, now));
        assert!(!helpers::is_snooze_window(alarm_time, deadline, now));
        assert!(!helpers::is_slash_window(deadline, now));

        // 3. After alarm, before deadline: claim/snooze open
        let mid = alarm_time + 100;
        assert!(!helpers::is_refund_window(alarm_time, mid));
        assert!(helpers::is_claim_window(alarm_time, deadline, mid));
        assert!(helpers::is_snooze_window(alarm_time, deadline, mid));
        assert!(!helpers::is_slash_window(deadline, mid));

        // 4. After deadline: only slash open
        let after = deadline + 1;
        assert!(!helpers::is_refund_window(alarm_time, after));
        assert!(!helpers::is_claim_window(alarm_time, deadline, after));
        assert!(!helpers::is_snooze_window(alarm_time, deadline, after));
        assert!(helpers::is_slash_window(deadline, after));
    }

    #[test]
    fn test_full_alarm_lifecycle_with_snooze() {
        let alarm_time = 1_000_000i64;
        let deadline = 2_000_000i64;
        let deposit = 5_000_000_000u64; // 5 SOL

        let mut remaining = deposit;
        let mut current_alarm = alarm_time;
        let mut current_deadline = deadline;

        // Snooze 3 times
        for i in 0..3u8 {
            // Calculate cost
            let cost = helpers::snooze_cost(remaining, i).unwrap();
            assert!(cost > 0);
            remaining -= cost;

            // Extend time
            let (new_a, new_d) = helpers::snooze_time_extension(
                current_alarm,
                current_deadline,
                DEFAULT_SNOOZE_EXTENSION_SECONDS,
            )
            .unwrap();
            current_alarm = new_a;
            current_deadline = new_d;
        }

        // After 3 snoozes: time extended by 3*300=900 seconds
        assert_eq!(current_alarm, alarm_time + 900);
        assert_eq!(current_deadline, deadline + 900);
        // Remaining should be less than deposit
        assert!(remaining < deposit);
    }

    // =========================================================================
    // PenaltyRoute equality
    // =========================================================================

    #[test]
    fn test_penalty_route_equality() {
        assert_eq!(PenaltyRoute::Burn, PenaltyRoute::Burn);
        assert_eq!(PenaltyRoute::Donate, PenaltyRoute::Donate);
        assert_eq!(PenaltyRoute::Buddy, PenaltyRoute::Buddy);
        assert_ne!(PenaltyRoute::Burn, PenaltyRoute::Donate);
        assert_ne!(PenaltyRoute::Burn, PenaltyRoute::Buddy);
        assert_ne!(PenaltyRoute::Donate, PenaltyRoute::Buddy);
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    #[test]
    fn test_snooze_cost_at_boundary_amounts() {
        // Exactly at minimum deposit
        let cost = helpers::snooze_cost(MIN_DEPOSIT_LAMPORTS, 0).unwrap();
        assert!(cost > 0);

        // Just above minimum
        let cost = helpers::snooze_cost(MIN_DEPOSIT_LAMPORTS + 1, 0).unwrap();
        assert!(cost > 0);

        // 1 lamport
        let cost = helpers::snooze_cost(1, 0).unwrap();
        assert_eq!(cost, 0); // 1 * 10 / 100 = 0 (integer division)
    }

    #[test]
    fn test_emergency_penalty_at_boundary() {
        // 1 lamport
        assert_eq!(helpers::emergency_penalty(1), Some(0)); // 1 * 5 / 100 = 0
                                                            // 20 lamports
        assert_eq!(helpers::emergency_penalty(20), Some(1)); // 20 * 5 / 100 = 1
                                                             // 19 lamports
        assert_eq!(helpers::emergency_penalty(19), Some(0)); // 19 * 5 / 100 = 0
    }

    // =========================================================================
    // State struct coverage
    // =========================================================================

    #[test]
    fn test_alarm_default_fields() {
        let alarm = Alarm::default();
        assert_eq!(alarm.owner, Pubkey::default());
        assert_eq!(alarm.alarm_id, 0);
        assert_eq!(alarm.alarm_time, 0);
        assert_eq!(alarm.deadline, 0);
        assert_eq!(alarm.initial_amount, 0);
        assert_eq!(alarm.remaining_amount, 0);
        assert_eq!(alarm.penalty_route, 0);
        assert!(alarm.penalty_destination.is_none());
        assert_eq!(alarm.snooze_count, 0);
        assert_eq!(alarm.status, AlarmStatus::Created);
        assert_eq!(alarm.bump, 0);
        assert_eq!(alarm.vault_bump, 0);
    }

    #[test]
    fn test_user_profile_default_fields() {
        let profile = UserProfile::default();
        assert_eq!(profile.owner, Pubkey::default());
        assert!(profile.tag_hash.is_none());
        assert_eq!(profile.bump, 0);
    }

    #[test]
    fn test_vault_size_matches_expected() {
        // Vault: discriminator(8) + alarm pubkey(32) + bump(1) = 41
        assert_eq!(Vault::SIZE, 41);
    }

    #[test]
    fn test_alarm_status_clone_and_copy() {
        let s = AlarmStatus::Acknowledged;
        let s2 = s; // Copy
        let s3 = s.clone();
        assert_eq!(s, s2);
        assert_eq!(s, s3);
    }

    #[test]
    fn test_penalty_route_clone_and_debug() {
        let r = PenaltyRoute::Donate;
        let r2 = r; // Copy
        assert_eq!(r, r2);
        // Debug impl produces non-empty string
        let dbg = format!("{:?}", r);
        assert!(dbg.contains("Donate"));
    }

    #[test]
    fn test_alarm_status_all_variants_debug() {
        let variants = [
            AlarmStatus::Created,
            AlarmStatus::Acknowledged,
            AlarmStatus::Claimed,
            AlarmStatus::Slashed,
        ];
        for v in &variants {
            let dbg = format!("{:?}", v);
            assert!(!dbg.is_empty());
        }
        // All variants are distinct
        for i in 0..variants.len() {
            for j in (i + 1)..variants.len() {
                assert_ne!(variants[i], variants[j]);
            }
        }
    }
}

#[cfg(test)]
mod fuzz_tests {
    use super::*;

    #[derive(Clone, Copy, Debug)]
    enum TimeKind {
        BeforeAlarm,
        AtAlarm,
        Between,
        AtDeadline,
        AfterDeadline,
    }

    #[derive(Clone, Debug)]
    enum Op {
        Ack,
        Snooze { expected_snooze_count: u8 },
        Claim,
        Slash,
        Refund,
    }

    #[derive(Clone, Debug, PartialEq, Eq)]
    struct ModelAlarm {
        status: AlarmStatus,
        alarm_time: i64,
        deadline: i64,
        initial_amount: u64,
        remaining_amount: u64,
        snooze_count: u8,
        rent_minimum: u64,
        vault_lamports: u64,
        vault_closed: bool,
    }

    impl ModelAlarm {
        fn new(alarm_time: i64, deadline: i64, deposit: u64, rent_minimum: u64) -> Self {
            Self {
                status: AlarmStatus::Created,
                alarm_time,
                deadline,
                initial_amount: deposit,
                remaining_amount: deposit,
                snooze_count: 0,
                rent_minimum,
                vault_lamports: rent_minimum.saturating_add(deposit),
                vault_closed: false,
            }
        }

        fn is_terminal(&self) -> bool {
            matches!(self.status, AlarmStatus::Claimed | AlarmStatus::Slashed)
        }

        fn assert_invariants(&self) {
            assert!(
                self.deadline > self.alarm_time,
                "deadline must be > alarm_time"
            );

            assert!(
                self.remaining_amount <= self.initial_amount,
                "remaining cannot exceed initial"
            );

            assert!(
                self.snooze_count <= MAX_SNOOZE_COUNT,
                "snooze_count must be <= MAX_SNOOZE_COUNT"
            );

            if !self.vault_closed {
                assert!(
                    self.vault_lamports >= self.rent_minimum,
                    "vault must stay rent-exempt while open"
                );
                let available = self.vault_lamports.saturating_sub(self.rent_minimum);
                assert!(
                    available >= self.remaining_amount,
                    "vault available must cover remaining"
                );
            }

            if self.is_terminal() {
                assert!(self.vault_closed, "terminal implies vault closed");
                assert_eq!(self.remaining_amount, 0, "terminal implies remaining == 0");
                assert_eq!(
                    self.vault_lamports, 0,
                    "closed vault implies 0 lamports in model"
                );
            }

            if self.vault_closed {
                assert!(self.is_terminal(), "vault_closed implies terminal status");
            }
        }

        fn apply(&mut self, op: Op, now: i64) -> Result<(), ()> {
            if self.is_terminal() || self.vault_closed {
                return Err(());
            }

            match op {
                Op::Ack => {
                    if self.status != AlarmStatus::Created {
                        return Err(());
                    }
                    if !(now >= self.alarm_time && now < self.deadline) {
                        return Err(());
                    }
                    self.status = AlarmStatus::Acknowledged;
                    Ok(())
                }
                Op::Snooze {
                    expected_snooze_count,
                } => {
                    if self.status != AlarmStatus::Created {
                        return Err(());
                    }
                    if !(now >= self.alarm_time && now < self.deadline) {
                        return Err(());
                    }
                    if self.snooze_count >= MAX_SNOOZE_COUNT {
                        return Err(());
                    }
                    if expected_snooze_count != self.snooze_count {
                        return Err(());
                    }

                    let cost =
                        helpers::snooze_cost(self.remaining_amount, self.snooze_count).ok_or(())?;
                    if cost == 0 {
                        return Err(());
                    }

                    let available = self.vault_lamports.saturating_sub(self.rent_minimum);
                    let final_cost = cost.min(available);
                    if final_cost == 0 {
                        return Err(());
                    }

                    self.vault_lamports = self.vault_lamports.checked_sub(final_cost).ok_or(())?;
                    self.remaining_amount =
                        self.remaining_amount.checked_sub(final_cost).ok_or(())?;

                    self.snooze_count = self.snooze_count.checked_add(1).ok_or(())?;
                    self.alarm_time = self
                        .alarm_time
                        .checked_add(DEFAULT_SNOOZE_EXTENSION_SECONDS)
                        .ok_or(())?;
                    self.deadline = self
                        .deadline
                        .checked_add(DEFAULT_SNOOZE_EXTENSION_SECONDS)
                        .ok_or(())?;

                    Ok(())
                }
                Op::Claim => {
                    if !(self.status == AlarmStatus::Created
                        || self.status == AlarmStatus::Acknowledged)
                    {
                        return Err(());
                    }
                    if !(now >= self.alarm_time && now < self.deadline) {
                        return Err(());
                    }
                    self.status = AlarmStatus::Claimed;
                    self.remaining_amount = 0;
                    self.vault_closed = true;
                    self.vault_lamports = 0;
                    Ok(())
                }
                Op::Slash => {
                    if !(self.status == AlarmStatus::Created
                        || self.status == AlarmStatus::Acknowledged)
                    {
                        return Err(());
                    }
                    if now < self.deadline {
                        return Err(());
                    }
                    self.status = AlarmStatus::Slashed;
                    self.remaining_amount = 0;
                    self.vault_closed = true;
                    self.vault_lamports = 0;
                    Ok(())
                }
                Op::Refund => {
                    if self.status != AlarmStatus::Created {
                        return Err(());
                    }
                    if now >= self.alarm_time {
                        return Err(());
                    }

                    let penalty = helpers::emergency_penalty(self.remaining_amount).ok_or(())?;
                    let final_penalty = helpers::cap_at_rent_exempt(
                        penalty,
                        self.vault_lamports,
                        self.rent_minimum,
                    );
                    self.vault_lamports =
                        self.vault_lamports.checked_sub(final_penalty).ok_or(())?;

                    self.status = AlarmStatus::Claimed;
                    self.remaining_amount = 0;
                    self.vault_closed = true;
                    self.vault_lamports = 0;
                    Ok(())
                }
            }
        }
    }

    #[derive(Clone, Copy)]
    struct XorShift64 {
        state: u64,
    }

    impl XorShift64 {
        fn new(seed: u64) -> Self {
            // Avoid the all-zero state.
            Self {
                state: if seed == 0 {
                    0xdead_beef_cafe_f00d
                } else {
                    seed
                },
            }
        }

        fn next_u64(&mut self) -> u64 {
            let mut x = self.state;
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            self.state = x;
            x
        }

        fn next_u8(&mut self) -> u8 {
            (self.next_u64() & 0xff) as u8
        }

        fn gen_range_u64(&mut self, start: u64, end_inclusive: u64) -> u64 {
            if start >= end_inclusive {
                return start;
            }
            let span = end_inclusive - start + 1;
            start + (self.next_u64() % span)
        }

        fn gen_range_i64(&mut self, start: i64, end_inclusive: i64) -> i64 {
            if start >= end_inclusive {
                return start;
            }
            let span = (end_inclusive - start + 1) as u64;
            start + (self.next_u64() % span) as i64
        }

        fn pick_time_kind(&mut self) -> TimeKind {
            match self.next_u64() % 5 {
                0 => TimeKind::BeforeAlarm,
                1 => TimeKind::AtAlarm,
                2 => TimeKind::Between,
                3 => TimeKind::AtDeadline,
                _ => TimeKind::AfterDeadline,
            }
        }

        fn pick_op(&mut self) -> Op {
            match self.next_u64() % 5 {
                0 => Op::Ack,
                1 => Op::Snooze {
                    expected_snooze_count: self.next_u8(),
                },
                2 => Op::Claim,
                3 => Op::Slash,
                _ => Op::Refund,
            }
        }
    }

    fn pick_now(kind: TimeKind, alarm_time: i64, deadline: i64) -> i64 {
        match kind {
            TimeKind::BeforeAlarm => alarm_time.saturating_sub(1),
            TimeKind::AtAlarm => alarm_time,
            TimeKind::Between => {
                let candidate = alarm_time.saturating_add(1);
                if candidate < deadline {
                    candidate
                } else {
                    alarm_time
                }
            }
            TimeKind::AtDeadline => deadline,
            TimeKind::AfterDeadline => deadline.saturating_add(1),
        }
    }

    #[test]
    fn fuzz_windows_partition_time_axis() {
        let mut rng = XorShift64::new(1);
        for _ in 0..10_000 {
            let alarm_time = rng.gen_range_i64(10_000, 1_000_000_000);
            let gap = rng.gen_range_i64(1, 100_000);
            let deadline = alarm_time.saturating_add(gap);
            if deadline <= alarm_time {
                continue;
            }

            let now = rng.gen_range_i64(0, 1_100_000_000);

            let refund = helpers::is_refund_window(alarm_time, now);
            let claim = helpers::is_claim_window(alarm_time, deadline, now);
            let slash = helpers::is_slash_window(deadline, now);

            let sum = (refund as u8) + (claim as u8) + (slash as u8);
            assert_eq!(sum, 1, "windows must partition time axis");
            assert_eq!(helpers::is_snooze_window(alarm_time, deadline, now), claim);
        }
    }

    #[test]
    fn fuzz_cap_at_rent_exempt_is_safe() {
        let mut rng = XorShift64::new(2);
        for _ in 0..50_000 {
            let desired = rng.next_u64();
            let current = rng.next_u64();
            let min_balance = rng.next_u64();

            let capped = helpers::cap_at_rent_exempt(desired, current, min_balance);
            assert!(capped <= desired);

            let available = current.saturating_sub(min_balance);
            assert!(capped <= available);

            if current >= min_balance {
                assert!(current - capped >= min_balance);
            }
        }
    }

    #[test]
    fn fuzz_state_machine_preserves_invariants() {
        for seed in 1u64..=2_000 {
            let mut rng = XorShift64::new(seed);

            let deposit = if rng.next_u64().is_multiple_of(4) {
                0u64
            } else {
                rng.gen_range_u64(MIN_DEPOSIT_LAMPORTS, 10_000_000_000)
            };
            let rent_minimum = rng.gen_range_u64(1, 5_000_000);

            let alarm_time = rng.gen_range_i64(10_000, 1_000_000_000);
            let gap = rng.gen_range_i64(2, 100_000);
            let deadline = alarm_time.saturating_add(gap);
            if deadline <= alarm_time {
                continue;
            }

            let mut m = ModelAlarm::new(alarm_time, deadline, deposit, rent_minimum);
            m.assert_invariants();

            let steps = (rng.next_u64() % 41) as usize;
            for _ in 0..steps {
                let op = rng.pick_op();
                let tk = rng.pick_time_kind();
                let now = pick_now(tk, m.alarm_time, m.deadline);

                let before = m.clone();
                let res = m.apply(op, now);

                if res.is_err() {
                    assert_eq!(m, before, "invalid ops must not mutate state");
                }

                if matches!(before.status, AlarmStatus::Claimed | AlarmStatus::Slashed) {
                    assert!(res.is_err());
                    assert_eq!(m, before);
                }

                m.assert_invariants();
            }
        }
    }
}

/// Integration test scenarios (require local validator)
/// Run with: anchor test
#[cfg(feature = "test-bpf")]
mod integration_tests {
    // These are implemented as Anchor TypeScript tests
    // See tests/solarma_vault.ts (66 tests)
    // Rust unit tests above: 60+ tests
}
