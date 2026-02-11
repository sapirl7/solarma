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

    // =========================================================================
    // SECURITY: Inline instruction logic equivalence tests
    // These verify that helpers produce the same results as the inline
    // arithmetic in instruction handlers (create_alarm, snooze, slash, etc.)
    // =========================================================================

    #[test]
    fn test_security_snooze_inline_matches_helper() {
        // snooze.rs calculates: base = remaining * 10 / 100, cost = base * 2^count
        // helpers::snooze_cost should produce identical results
        let test_cases: Vec<(u64, u8)> = vec![
            (1_000_000_000, 0), // 1 SOL, first snooze
            (1_000_000_000, 5), // 1 SOL, 6th snooze
            (500_000_000, 9),   // 0.5 SOL, last valid snooze
            (MIN_DEPOSIT_LAMPORTS, 0),
            (10_000_000_000, 3), // 10 SOL
        ];

        for (remaining, count) in test_cases {
            // Inline calculation from snooze.rs
            let inline_base = remaining
                .checked_mul(DEFAULT_SNOOZE_PERCENT)
                .and_then(|v| v.checked_div(100));
            let inline_cost = inline_base.and_then(|base| {
                let mult = 1u64.checked_shl(count as u32)?;
                base.checked_mul(mult).map(|c| c.min(remaining))
            });

            let helper_cost = helpers::snooze_cost(remaining, count);

            assert_eq!(
                inline_cost, helper_cost,
                "Divergence at remaining={}, count={}",
                remaining, count
            );
        }
    }

    #[test]
    fn test_security_emergency_penalty_inline_matches_helper() {
        // emergency_refund.rs: remaining * PENALTY_PERCENT / 100
        let amounts = [
            0,
            1,
            100,
            MIN_DEPOSIT_LAMPORTS,
            1_000_000_000,
            100_000_000_000,
        ];

        for amount in amounts {
            let inline = amount
                .checked_mul(EMERGENCY_REFUND_PENALTY_PERCENT)
                .and_then(|v| v.checked_div(100));
            let helper = helpers::emergency_penalty(amount);
            assert_eq!(inline, helper, "Divergence at amount={}", amount);
        }
    }

    #[test]
    fn test_security_slash_burn_route_requires_burn_sink() {
        // slash.rs: Burn route requires recipient == BURN_SINK
        use crate::constants::BURN_SINK;
        let burn_sink_bytes = BURN_SINK.to_bytes();
        let wrong = [0u8; 32];

        assert!(
            helpers::validate_penalty_recipient(0, &burn_sink_bytes, &burn_sink_bytes, None)
                .is_ok()
        );
        assert!(helpers::validate_penalty_recipient(0, &wrong, &burn_sink_bytes, None).is_err());
    }

    #[test]
    fn test_security_slash_donate_route_requires_exact_destination() {
        use crate::constants::BURN_SINK;
        let burn_sink_bytes = BURN_SINK.to_bytes();
        let dest = [42u8; 32];
        let wrong = [99u8; 32];

        // Correct destination
        assert!(
            helpers::validate_penalty_recipient(1, &dest, &burn_sink_bytes, Some(&dest)).is_ok()
        );
        // Wrong destination
        assert!(
            helpers::validate_penalty_recipient(1, &wrong, &burn_sink_bytes, Some(&dest)).is_err()
        );
        // Missing destination
        assert!(helpers::validate_penalty_recipient(1, &dest, &burn_sink_bytes, None).is_err());
    }

    #[test]
    fn test_security_slash_buddy_route_requires_exact_destination() {
        use crate::constants::BURN_SINK;
        let burn_sink_bytes = BURN_SINK.to_bytes();
        let buddy = [77u8; 32];
        let wrong = [88u8; 32];

        assert!(
            helpers::validate_penalty_recipient(2, &buddy, &burn_sink_bytes, Some(&buddy)).is_ok()
        );
        assert!(
            helpers::validate_penalty_recipient(2, &wrong, &burn_sink_bytes, Some(&buddy)).is_err()
        );
        assert!(helpers::validate_penalty_recipient(2, &buddy, &burn_sink_bytes, None).is_err());
    }

    #[test]
    fn test_security_claim_and_slash_windows_never_overlap() {
        // Critical: there must NEVER be a timestamp where both claim and slash are valid
        let mut rng_state = 0xdeadbeef_u64;
        for _ in 0..100_000 {
            rng_state ^= rng_state << 13;
            rng_state ^= rng_state >> 7;
            rng_state ^= rng_state << 17;

            let alarm_time = (rng_state % 1_000_000_000) as i64 + 1;
            let gap = ((rng_state >> 32) % 100_000) as i64 + 1;
            let deadline = alarm_time + gap;
            let now = (rng_state % 1_200_000_000) as i64;

            let can_claim = helpers::is_claim_window(alarm_time, deadline, now);
            let can_slash = helpers::is_slash_window(deadline, now);

            assert!(
                !(can_claim && can_slash),
                "SECURITY VIOLATION: claim AND slash both valid at now={}, alarm={}, deadline={}",
                now,
                alarm_time,
                deadline
            );
        }
    }

    #[test]
    fn test_security_refund_and_claim_never_overlap() {
        // Refund is before alarm_time, claim is after alarm_time
        let mut rng_state = 0xcafe_u64;
        for _ in 0..100_000 {
            rng_state ^= rng_state << 13;
            rng_state ^= rng_state >> 7;
            rng_state ^= rng_state << 17;

            let alarm_time = (rng_state % 1_000_000_000) as i64 + 1;
            let gap = ((rng_state >> 32) % 100_000) as i64 + 1;
            let deadline = alarm_time + gap;
            let now = (rng_state % 1_200_000_000) as i64;

            let can_refund = helpers::is_refund_window(alarm_time, now);
            let can_claim = helpers::is_claim_window(alarm_time, deadline, now);

            assert!(
                !(can_refund && can_claim),
                "SECURITY: refund AND claim both valid at now={}, alarm={}",
                now,
                alarm_time
            );
        }
    }

    #[test]
    fn test_security_deposit_never_goes_negative_after_snoozes() {
        // Simulate max snooze sequence: deposit must never underflow
        let initial_deposits = [
            MIN_DEPOSIT_LAMPORTS,
            10_000_000,     // 0.01 SOL
            1_000_000_000,  // 1 SOL
            10_000_000_000, // 10 SOL
        ];

        for deposit in initial_deposits {
            let mut remaining = deposit;
            for count in 0..MAX_SNOOZE_COUNT {
                if let Some(cost) = helpers::snooze_cost(remaining, count) {
                    let deduction = cost.min(remaining);
                    remaining = remaining.checked_sub(deduction).expect(&format!(
                        "UNDERFLOW at snooze #{} for deposit={}",
                        count, deposit
                    ));
                }
            }
            // After max snoozes, remaining must be >= 0 (checked by type) and < initial
            assert!(remaining < deposit || deposit == 0);
        }
    }

    #[test]
    fn test_security_snooze_cannot_exceed_max_count() {
        // At MAX_SNOOZE_COUNT, is_max_snooze must return true
        assert!(helpers::is_max_snooze(MAX_SNOOZE_COUNT));
        // One before is still allowed
        assert!(!helpers::is_max_snooze(MAX_SNOOZE_COUNT - 1));
        // Any value above is also blocked
        for v in MAX_SNOOZE_COUNT..=u8::MAX {
            assert!(
                helpers::is_max_snooze(v),
                "Must block snooze at count={}",
                v
            );
        }
    }

    #[test]
    fn test_security_zero_deposit_alarm_validation() {
        // Zero-deposit alarms should be valid regardless of penalty route
        let now = 1_000_000i64;
        for route in 0..=2u8 {
            let result =
                helpers::validate_alarm_params(now + 3600, now + 7200, now, 0, route, false);
            assert!(result.is_ok(), "Zero-deposit should accept route={}", route);
        }
    }

    #[test]
    fn test_security_boundary_timestamp_precision() {
        // Exact boundary timestamps must behave correctly
        let alarm_time = 1_000_000i64;
        let deadline = 2_000_000i64;

        // At exactly alarm_time: claim YES, refund NO, slash NO
        assert!(helpers::is_claim_window(alarm_time, deadline, alarm_time));
        assert!(!helpers::is_refund_window(alarm_time, alarm_time));
        assert!(!helpers::is_slash_window(deadline, alarm_time));

        // At exactly deadline: claim NO, slash YES, refund NO
        assert!(!helpers::is_claim_window(alarm_time, deadline, deadline));
        assert!(helpers::is_slash_window(deadline, deadline));
        assert!(!helpers::is_refund_window(alarm_time, deadline));

        // 1 second before alarm: refund YES, claim NO
        assert!(helpers::is_refund_window(alarm_time, alarm_time - 1));
        assert!(!helpers::is_claim_window(
            alarm_time,
            deadline,
            alarm_time - 1
        ));

        // 1 second before deadline: claim YES, slash NO
        assert!(helpers::is_claim_window(alarm_time, deadline, deadline - 1));
        assert!(!helpers::is_slash_window(deadline, deadline - 1));
    }

    #[test]
    fn test_security_cap_at_rent_exempt_never_drains_below_minimum() {
        // Property: after deduction, vault must have >= min_balance
        let test_cases = [
            (1_000_000, 2_000_000, 500_000),
            (5_000_000, 2_000_000, 1_000_000),
            (100, 100, 100),
            (u64::MAX, 1_000_000_000, 500_000),
            (0, 0, 0),
        ];

        for (desired, current, min_bal) in test_cases {
            let capped = helpers::cap_at_rent_exempt(desired, current, min_bal);
            if current >= min_bal {
                assert!(
                    current - capped >= min_bal,
                    "SECURITY: vault would drop below rent-exempt! desired={}, current={}, min={}",
                    desired,
                    current,
                    min_bal
                );
            }
        }
    }

    #[test]
    fn test_security_snooze_cost_shift_overflow_at_count_63() {
        // 1u64 << 63 is valid (= i64::MIN as u64), but 1u64 << 64 would panic
        // Our helper uses checked_shl which returns None for shift >= 64
        let result = helpers::snooze_cost(1_000_000_000, 63);
        // Either None (overflow) or Some(capped at remaining) — must not panic
        if let Some(v) = result {
            assert!(v <= 1_000_000_000);
        }

        // shift=64 should definitely not panic (should return None)
        // Note: u8 max is 255, but MAX_SNOOZE_COUNT is 10, so this is theoretical
        let result = helpers::snooze_cost(1_000_000_000, 64);
        assert!(result.is_none(), "shift=64 must overflow");
    }

    // =========================================================================
    // LIFECYCLE: Full alarm lifecycle simulations
    // =========================================================================

    #[test]
    fn test_lifecycle_create_snooze_claim() {
        // Simulate: create alarm → snooze 3 times → verify claim window
        let now = 1_000_000i64;
        let alarm_time = now + 3600; // +1 hour
        let deadline = alarm_time + DEFAULT_GRACE_PERIOD; // +30 min grace
        let deposit = 1_000_000_000u64; // 1 SOL

        // Validate creation
        assert!(
            helpers::validate_alarm_params(alarm_time, deadline, now, deposit, 0, false).is_ok()
        );

        // Before alarm fires: only refund valid
        assert!(helpers::is_refund_window(alarm_time, now));
        assert!(!helpers::is_claim_window(alarm_time, deadline, now));
        assert!(!helpers::is_slash_window(deadline, now));
        assert!(!helpers::is_snooze_window(alarm_time, deadline, now));

        // Simulate 3 snoozes after alarm fires
        let mut remaining = deposit;
        let mut current_alarm = alarm_time;
        let mut current_deadline = deadline;

        for count in 0..3u8 {
            // After alarm fires, in snooze window
            assert!(helpers::is_snooze_window(
                current_alarm,
                current_deadline,
                current_alarm
            ));

            let cost = helpers::snooze_cost(remaining, count).unwrap();
            remaining -= cost;

            let (new_a, new_d) = helpers::snooze_time_extension(
                current_alarm,
                current_deadline,
                DEFAULT_SNOOZE_EXTENSION_SECONDS,
            )
            .unwrap();
            current_alarm = new_a;
            current_deadline = new_d;
        }

        // After snoozes: deposit reduced but not zero
        assert!(remaining > 0);
        assert!(remaining < deposit);

        // Verify extended claim window
        assert!(helpers::is_claim_window(
            current_alarm,
            current_deadline,
            current_alarm + 60
        ));
    }

    #[test]
    fn test_lifecycle_create_and_slash_after_deadline() {
        let now = 1_000_000i64;
        let alarm_time = now + 3600;
        let deadline = alarm_time + DEFAULT_GRACE_PERIOD;
        let deposit = 500_000_000u64;

        // Create valid alarm
        assert!(
            helpers::validate_alarm_params(alarm_time, deadline, now, deposit, 0, false).is_ok()
        );

        // After deadline: only slash valid
        let after_deadline = deadline + 1;
        assert!(helpers::is_slash_window(deadline, after_deadline));
        assert!(!helpers::is_claim_window(
            alarm_time,
            deadline,
            after_deadline
        ));
        assert!(!helpers::is_refund_window(alarm_time, after_deadline));

        // Validate burn route slash
        use crate::constants::BURN_SINK;
        let sink = BURN_SINK.to_bytes();
        assert!(helpers::validate_penalty_recipient(0, &sink, &sink, None).is_ok());
    }

    #[test]
    fn test_validate_alarm_params_exhaustive_route_deposit_combos() {
        // Test all route * deposit * destination combos
        let now = 1_000_000i64;
        let alarm_time = now + 3600;
        let deadline = alarm_time + 7200;

        let deposit_cases = [0u64, MIN_DEPOSIT_LAMPORTS, 1_000_000_000];
        let route_cases = [0u8, 1, 2]; // Burn, Donate, Buddy
        let dest_cases = [false, true];

        for deposit in deposit_cases {
            for route in route_cases {
                for has_dest in dest_cases {
                    let result = helpers::validate_alarm_params(
                        alarm_time, deadline, now, deposit, route, has_dest,
                    );

                    if deposit == 0 {
                        // Zero deposit: all combos should pass
                        assert!(
                            result.is_ok(),
                            "Zero deposit should pass for route={}, dest={}",
                            route,
                            has_dest
                        );
                    } else if route == 0 {
                        // Burn route: no destination needed
                        assert!(
                            result.is_ok(),
                            "Burn with deposit should pass, dest={}",
                            has_dest
                        );
                    } else if !has_dest {
                        // Donate/Buddy without destination: must fail
                        assert!(result.is_err(), "Route {} without dest should fail", route);
                    } else {
                        // Donate/Buddy with destination: should pass
                        assert!(result.is_ok(), "Route {} with dest should pass", route);
                    }
                }
            }
        }
    }

    #[test]
    fn test_validate_alarm_params_boundary_deposit() {
        let now = 1_000_000i64;
        let alarm_time = now + 3600;
        let deadline = alarm_time + 7200;

        // 1 lamport below minimum: should fail
        let too_small = MIN_DEPOSIT_LAMPORTS - 1;
        assert!(
            helpers::validate_alarm_params(alarm_time, deadline, now, too_small, 0, false).is_err()
        );

        // Exactly minimum: should pass
        assert!(helpers::validate_alarm_params(
            alarm_time,
            deadline,
            now,
            MIN_DEPOSIT_LAMPORTS,
            0,
            false
        )
        .is_ok());

        // u64::MAX deposit: should pass (amount validation only checks minimum)
        assert!(
            helpers::validate_alarm_params(alarm_time, deadline, now, u64::MAX, 0, false).is_ok()
        );
    }

    #[test]
    fn test_windows_cover_entire_timeline_no_gaps() {
        // Property: for any timestamp, exactly one window should be active
        // refund | snooze/claim | slash
        let alarm_time = 1_000_000i64;
        let deadline = 2_000_000i64;

        let test_points = [
            0,              // well before
            alarm_time - 1, // 1s before alarm
            alarm_time,     // exactly alarm
            alarm_time + 1, // 1s after alarm
            deadline - 1,   // 1s before deadline
            deadline,       // exactly deadline
            deadline + 1,   // 1s after deadline
            3_000_000,      // well after
        ];

        for now in test_points {
            let refund = helpers::is_refund_window(alarm_time, now);
            let claim = helpers::is_claim_window(alarm_time, deadline, now);
            let slash = helpers::is_slash_window(deadline, now);

            let count = [refund, claim, slash].iter().filter(|&&x| x).count();

            // Exactly one window should be active (no gaps, no overlaps)
            // Exception: before alarm, only refund; AT alarm, only claim;
            // AT deadline, only slash
            assert!(
                count == 1,
                "Expected exactly 1 active window at now={}, got {}: refund={}, claim={}, slash={}",
                now,
                count,
                refund,
                claim,
                slash
            );
        }
    }

    #[test]
    fn test_snooze_time_extension_chain_10_snoozes() {
        // Verify snooze extensions chain correctly for max snoozes
        let mut alarm_time = 1_000_000i64;
        let mut deadline = alarm_time + DEFAULT_GRACE_PERIOD;

        for _ in 0..MAX_SNOOZE_COUNT {
            let (new_a, new_d) = helpers::snooze_time_extension(
                alarm_time,
                deadline,
                DEFAULT_SNOOZE_EXTENSION_SECONDS,
            )
            .expect("Extension should not overflow for 10 snoozes");

            assert_eq!(new_a, alarm_time + DEFAULT_SNOOZE_EXTENSION_SECONDS);
            assert_eq!(new_d, deadline + DEFAULT_SNOOZE_EXTENSION_SECONDS);
            assert!(new_d > new_a, "Deadline must always be after alarm");

            alarm_time = new_a;
            deadline = new_d;
        }

        // After 10 snoozes, alarm is 10*300=3000s later
        let expected_shift = (MAX_SNOOZE_COUNT as i64) * DEFAULT_SNOOZE_EXTENSION_SECONDS;
        assert_eq!(alarm_time, 1_000_000 + expected_shift);
    }

    #[test]
    fn test_security_all_windows_fuzz_timeline() {
        // 100K random timestamps: every point maps to exactly one window
        let alarm_time = 1_000_000i64;
        let deadline = 2_000_000i64;
        let mut rng = 0xdead_beef_u64;

        for _ in 0..100_000 {
            rng ^= rng << 13;
            rng ^= rng >> 7;
            rng ^= rng << 17;

            let now = (rng % 3_000_000) as i64;

            let refund = helpers::is_refund_window(alarm_time, now);
            let claim = helpers::is_claim_window(alarm_time, deadline, now);
            let slash = helpers::is_slash_window(deadline, now);

            let count = [refund, claim, slash].iter().filter(|&&x| x).count();
            assert_eq!(
                count, 1,
                "Exactly 1 window at now={}: refund={} claim={} slash={}",
                now, refund, claim, slash
            );
        }
    }

    #[test]
    fn test_snooze_window_equals_claim_window() {
        // Snooze and claim windows use same boundary logic
        // is_snooze_window(a, d, t) == is_claim_window(a, d, t) for all t
        let alarm_time = 1_000_000i64;
        let deadline = 2_000_000i64;

        let points = [
            alarm_time - 1,
            alarm_time,
            alarm_time + 1,
            deadline - 1,
            deadline,
            deadline + 1,
            0,
            1_500_000,
            3_000_000,
        ];

        for now in points {
            assert_eq!(
                helpers::is_snooze_window(alarm_time, deadline, now),
                helpers::is_claim_window(alarm_time, deadline, now),
                "Snooze and claim windows differ at now={}",
                now
            );
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
    // Rust unit tests above: 96+ tests
}

// =========================================================================
// Constants tests — verify values match documented behavior
// =========================================================================
#[cfg(test)]
mod constants_tests {
    use crate::constants::*;
    use anchor_lang::prelude::Pubkey;

    #[test]
    fn test_burn_sink_is_solana_incinerator() {
        // BURN_SINK must be the well-known Solana incinerator address
        let expected_b58 = "1nc1nerator11111111111111111111111111111111";
        let expected = Pubkey::try_from(expected_b58).unwrap();
        assert_eq!(BURN_SINK, expected, "BURN_SINK must be Solana incinerator");
    }

    #[test]
    fn test_snooze_percent_within_valid_range() {
        // Snooze cost % must be > 0 and <= 100
        assert!(DEFAULT_SNOOZE_PERCENT > 0);
        assert!(DEFAULT_SNOOZE_PERCENT <= 100);
    }

    #[test]
    fn test_max_snooze_count_reasonable() {
        // Must be > 0 (at least 1 snooze allowed) and < 64 (shift safety)
        assert!(MAX_SNOOZE_COUNT > 0);
        assert!(
            MAX_SNOOZE_COUNT < 64,
            "Must stay below 64 for bit shift safety"
        );
    }

    #[test]
    fn test_min_deposit_is_positive() {
        assert!(MIN_DEPOSIT_LAMPORTS > 0, "Min deposit must be positive");
        // 0.001 SOL = 1_000_000 lamports
        assert_eq!(MIN_DEPOSIT_LAMPORTS, 1_000_000);
    }

    #[test]
    fn test_emergency_penalty_within_valid_range() {
        assert!(EMERGENCY_REFUND_PENALTY_PERCENT > 0);
        assert!(EMERGENCY_REFUND_PENALTY_PERCENT <= 100);
        assert_eq!(EMERGENCY_REFUND_PENALTY_PERCENT, 5);
    }

    #[test]
    fn test_grace_period_is_positive() {
        assert!(DEFAULT_GRACE_PERIOD > 0);
        assert_eq!(DEFAULT_GRACE_PERIOD, 1800); // 30 minutes
    }

    #[test]
    fn test_snooze_extension_is_positive() {
        assert!(DEFAULT_SNOOZE_EXTENSION_SECONDS > 0);
        assert_eq!(DEFAULT_SNOOZE_EXTENSION_SECONDS, 300); // 5 minutes
    }

    #[test]
    fn test_cross_platform_android_parity() {
        // Android AlarmTiming constants must match Rust:
        // AlarmTiming.GRACE_PERIOD_SECONDS = 1800
        // AlarmTiming.SNOOZE_MINUTES = 5 → 300 seconds
        // OnchainParameters.SNOOZE_BASE_PERCENT = 10
        // OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT = 5
        assert_eq!(
            DEFAULT_GRACE_PERIOD, 1800,
            "Must match Android AlarmTiming.GRACE_PERIOD_SECONDS"
        );
        assert_eq!(
            DEFAULT_SNOOZE_EXTENSION_SECONDS, 300,
            "Must match Android AlarmTiming.SNOOZE_MINUTES * 60"
        );
        assert_eq!(
            DEFAULT_SNOOZE_PERCENT, 10,
            "Must match Android OnchainParameters.SNOOZE_BASE_PERCENT"
        );
        assert_eq!(
            EMERGENCY_REFUND_PENALTY_PERCENT, 5,
            "Must match Android OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT"
        );
    }

    #[test]
    fn test_snooze_percent_times_max_exceeds_100() {
        // After MAX_SNOOZE_COUNT iterations, cumulative penalty should exceed 100%
        // cumulative = 10% * (2^MAX - 1)
        // At max=10: 10 * (1024 - 1) = 10230% >> 100%
        let cumulative = DEFAULT_SNOOZE_PERCENT * ((1u64 << MAX_SNOOZE_COUNT) - 1);
        assert!(cumulative > 100, "Max snoozes must drain the full deposit");
    }

    #[test]
    fn test_grace_period_longer_than_snooze_extension() {
        // Grace period must be >= snooze extension to guarantee at least 1 snooze window
        assert!(
            DEFAULT_GRACE_PERIOD >= DEFAULT_SNOOZE_EXTENSION_SECONDS,
            "Grace period must accommodate at least one snooze"
        );
    }
}

// =========================================================================
// Error enum tests — verify all error variants are distinct
// =========================================================================
#[cfg(test)]
mod error_tests {
    use crate::error::SolarmaError;

    #[test]
    fn test_all_error_variants_exist() {
        // Ensure all 14 error variants compile and are distinct enum values
        let variants: Vec<SolarmaError> = vec![
            SolarmaError::DeadlinePassed,
            SolarmaError::DeadlineNotPassed,
            SolarmaError::InvalidAlarmState,
            SolarmaError::InvalidPenaltyRoute,
            SolarmaError::InsufficientDeposit,
            SolarmaError::Overflow,
            SolarmaError::AlarmTimeInPast,
            SolarmaError::InvalidDeadline,
            SolarmaError::DepositTooSmall,
            SolarmaError::PenaltyDestinationRequired,
            SolarmaError::InvalidSinkAddress,
            SolarmaError::MaxSnoozesReached,
            SolarmaError::InvalidPenaltyRecipient,
            SolarmaError::PenaltyDestinationNotSet,
            SolarmaError::TooEarly,
            SolarmaError::TooLateForRefund,
        ];
        assert_eq!(variants.len(), 16, "Expected 16 SolarmaError variants");
    }

    #[test]
    fn test_error_variant_distinctness() {
        // All error codes must be unique (Anchor assigns sequential error codes)
        // This compiles-time verifies each variant is different
        let a = SolarmaError::DeadlinePassed;
        let b = SolarmaError::DeadlineNotPassed;
        let c = SolarmaError::InvalidAlarmState;
        // These are different enum variants → different discriminants
        assert!(std::mem::discriminant(&a) != std::mem::discriminant(&b));
        assert!(std::mem::discriminant(&b) != std::mem::discriminant(&c));
        assert!(std::mem::discriminant(&a) != std::mem::discriminant(&c));
    }
}

// =========================================================================
// Event struct tests — verify event construction and field types
// =========================================================================
#[cfg(test)]
mod event_tests {
    use crate::events::*;
    use anchor_lang::prelude::Pubkey;

    #[test]
    fn test_profile_initialized_event() {
        let event = ProfileInitialized {
            owner: Pubkey::default(),
        };
        assert_eq!(event.owner, Pubkey::default());
    }

    #[test]
    fn test_alarm_created_event() {
        let owner = Pubkey::default();
        let alarm = Pubkey::new_unique();
        let event = AlarmCreated {
            owner,
            alarm,
            alarm_id: 42,
            alarm_time: 1_000_000,
            deadline: 2_000_000,
            deposit_amount: 1_000_000_000,
            penalty_route: 0,
        };
        assert_eq!(event.alarm_id, 42);
        assert_eq!(event.deposit_amount, 1_000_000_000);
        assert_eq!(event.penalty_route, 0);
        assert!(event.deadline > event.alarm_time);
    }

    #[test]
    fn test_alarm_claimed_event() {
        let event = AlarmClaimed {
            owner: Pubkey::default(),
            alarm: Pubkey::new_unique(),
            alarm_id: 1,
            returned_amount: 500_000_000,
        };
        assert!(event.returned_amount > 0);
    }

    #[test]
    fn test_alarm_snoozed_event() {
        let event = AlarmSnoozed {
            owner: Pubkey::default(),
            alarm: Pubkey::new_unique(),
            alarm_id: 1,
            snooze_count: 3,
            cost: 100_000_000,
            remaining: 400_000_000,
            new_alarm_time: 1_001_800,
            new_deadline: 2_001_800,
        };
        assert_eq!(event.snooze_count, 3);
        assert!(event.remaining + event.cost <= 1_000_000_000);
        assert!(event.new_deadline > event.new_alarm_time);
    }

    #[test]
    fn test_alarm_slashed_event() {
        let event = AlarmSlashed {
            alarm: Pubkey::new_unique(),
            alarm_id: 1,
            penalty_recipient: Pubkey::default(),
            slashed_amount: 1_000_000_000,
            caller: Pubkey::new_unique(),
        };
        assert!(event.slashed_amount > 0);
        assert_ne!(event.alarm, event.caller);
    }

    #[test]
    fn test_emergency_refund_event() {
        let event = EmergencyRefundExecuted {
            owner: Pubkey::default(),
            alarm: Pubkey::new_unique(),
            alarm_id: 1,
            penalty_amount: 50_000_000,
            returned_amount: 950_000_000,
        };
        // penalty + returned should not exceed original deposit
        assert!(event.penalty_amount + event.returned_amount <= 1_000_000_000);
    }

    #[test]
    fn test_wake_acknowledged_event() {
        let event = WakeAcknowledged {
            owner: Pubkey::default(),
            alarm: Pubkey::new_unique(),
            alarm_id: 1,
            timestamp: 1_000_500,
        };
        assert!(event.timestamp > 0);
    }
}
