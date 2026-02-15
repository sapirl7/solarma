//! Property-based (fuzz) tests for helpers.rs
//!
//! Uses proptest to generate thousands of random inputs and validate
//! domain invariants. Runs on stable Rust via `cargo test`.
//!
//! Each test case is run 10,000 times by default (configurable via
//! PROPTEST_CASES env var).

#[cfg(test)]
mod prop_tests {
    use crate::helpers::*;
    use proptest::prelude::*;

    // =====================================================================
    // Snooze cost invariants
    // =====================================================================

    proptest! {
        #[test]
        fn snooze_cost_never_exceeds_remaining(
            remaining in 0u64..=u64::MAX,
            count in 0u8..=20u8
        ) {
            if let Some(cost) = snooze_cost(remaining, count) {
                prop_assert!(
                    cost <= remaining,
                    "cost {} exceeded remaining {}", cost, remaining
                );
            }
            // None (overflow) is acceptable — must not panic
        }

        #[test]
        fn snooze_cost_zero_remaining_yields_zero(count in 0u8..=20u8) {
            let cost = snooze_cost(0, count);
            prop_assert_eq!(cost, Some(0));
        }

        #[test]
        fn snooze_cost_monotonic_in_count(
            remaining in 1u64..=1_000_000_000u64,
            count in 0u8..=8u8
        ) {
            // Cost should increase (or stay equal/overflow) with higher snooze count
            let cost_low = snooze_cost(remaining, count);
            let cost_high = snooze_cost(remaining, count + 1);
            if let (Some(low), Some(high)) = (cost_low, cost_high) {
                prop_assert!(
                    high >= low,
                    "cost decreased: count={} cost={}, count+1={} cost={}",
                    count, low, count + 1, high
                );
            }
        }
    }

    // =====================================================================
    // Emergency penalty invariants
    // =====================================================================

    proptest! {
        #[test]
        fn emergency_penalty_never_exceeds_remaining(remaining in 0u64..=u64::MAX) {
            if let Some(penalty) = emergency_penalty(remaining) {
                prop_assert!(
                    penalty <= remaining,
                    "penalty {} exceeded remaining {}", penalty, remaining
                );
            }
        }

        #[test]
        fn emergency_penalty_is_five_percent(remaining in 0u64..=u64::MAX / 100) {
            // For values that won't overflow: penalty == remaining * 5 / 100
            let penalty = emergency_penalty(remaining).unwrap();
            let expected = remaining * 5 / 100;
            prop_assert_eq!(penalty, expected);
        }
    }

    // =====================================================================
    // Time window mutual exclusion invariants
    // =====================================================================

    proptest! {
        #[test]
        fn claim_grace_is_superset_of_claim(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            let claim = is_claim_window(alarm_time, deadline, current_time);
            let claim_grace = is_claim_window_with_grace(alarm_time, deadline, current_time);

            if claim {
                prop_assert!(
                    claim_grace,
                    "claim=true but claim_grace=false at alarm={}, deadline={}, now={}",
                    alarm_time, deadline, current_time
                );
            }
        }

        #[test]
        fn refund_and_slash_mutually_exclusive(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            // Only valid when alarm <= deadline (correct param ordering)
            if alarm_time <= deadline {
                let refund = is_refund_window(alarm_time, current_time);
                let slash = is_slash_window(deadline, current_time);
                prop_assert!(
                    !(refund && slash),
                    "refund AND slash both true at alarm={}, deadline={}, now={}",
                    alarm_time, deadline, current_time
                );
            }
        }

        #[test]
        fn buddy_only_implies_slash(
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            let buddy = is_buddy_only_window(deadline, current_time);
            let slash = is_slash_window(deadline, current_time);

            if buddy {
                prop_assert!(
                    slash,
                    "buddy=true but slash=false at deadline={}, now={}",
                    deadline, current_time
                );
            }
        }

        #[test]
        fn snooze_and_slash_mutually_exclusive(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            if alarm_time <= deadline {
                let snooze = is_snooze_window(alarm_time, deadline, current_time);
                let slash = is_slash_window(deadline, current_time);
                prop_assert!(
                    !(snooze && slash),
                    "snooze AND slash both true at alarm={}, deadline={}, now={}",
                    alarm_time, deadline, current_time
                );
            }
        }

        #[test]
        fn sweep_and_claim_grace_mutually_exclusive(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            let sweep = is_sweep_window(deadline, current_time);
            let claim_grace = is_claim_window_with_grace(alarm_time, deadline, current_time);

            if sweep {
                prop_assert!(
                    !claim_grace,
                    "sweep AND claim_grace both true at alarm={}, deadline={}, now={}",
                    alarm_time, deadline, current_time
                );
            }
        }

        #[test]
        fn time_windows_never_panic(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY
        ) {
            // Every function must handle any i64 without panicking
            let _ = is_claim_window(alarm_time, deadline, current_time);
            let _ = is_claim_window_with_grace(alarm_time, deadline, current_time);
            let _ = is_sweep_window(deadline, current_time);
            let _ = is_slash_window(deadline, current_time);
            let _ = is_buddy_only_window(deadline, current_time);
            let _ = is_refund_window(alarm_time, current_time);
            let _ = is_snooze_window(alarm_time, deadline, current_time);
            let _ = claim_deadline_with_grace(deadline);
        }
    }

    // =====================================================================
    // validate_alarm_params invariants
    // =====================================================================

    proptest! {
        #[test]
        fn validate_alarm_never_panics(
            alarm_time in prop::num::i64::ANY,
            deadline in prop::num::i64::ANY,
            current_time in prop::num::i64::ANY,
            deposit in prop::num::u64::ANY,
            penalty_route in 0u8..=10u8,
            has_dest in prop::bool::ANY
        ) {
            let _ = validate_alarm_params(
                alarm_time, deadline, current_time,
                deposit, penalty_route, has_dest,
            );
        }

        #[test]
        fn valid_alarm_satisfies_ordering(
            alarm_time in 1i64..=i64::MAX - 1,
            delta in 1i64..=10_000i64,
            deposit in 1_000_000u64..=1_000_000_000u64,
            route in 0u8..=2u8
        ) {
            let current_time = alarm_time - 1;
            let deadline = alarm_time + delta;
            let has_dest = route >= 1; // Donate and Buddy need destination

            let result = validate_alarm_params(
                alarm_time, deadline, current_time,
                deposit, route, has_dest,
            );
            prop_assert!(result.is_ok(), "valid params rejected: {:?}", result);
        }
    }

    // =====================================================================
    // Snooze time extension invariants
    // =====================================================================

    proptest! {
        #[test]
        fn snooze_extension_preserves_ordering(
            alarm_time in -1_000_000_000i64..=1_000_000_000i64,
            gap in 1i64..=100_000i64,
            extension in 0i64..=100_000i64
        ) {
            let deadline = alarm_time + gap;
            if let Some((new_alarm, new_deadline)) = snooze_time_extension(alarm_time, deadline, extension) {
                prop_assert!(
                    new_alarm <= new_deadline,
                    "extension broke ordering: new_alarm={} > new_deadline={}",
                    new_alarm, new_deadline
                );
                prop_assert!(
                    new_alarm >= alarm_time,
                    "positive extension decreased alarm_time"
                );
            }
        }
    }

    // =====================================================================
    // Cap at rent exempt invariants
    // =====================================================================

    proptest! {
        #[test]
        fn cap_never_exceeds_desired(
            desired in 0u64..=u64::MAX,
            current_lamports in 0u64..=u64::MAX,
            min_balance in 0u64..=u64::MAX
        ) {
            let capped = cap_at_rent_exempt(desired, current_lamports, min_balance);
            prop_assert!(capped <= desired, "capped {} > desired {}", capped, desired);
        }

        #[test]
        fn cap_preserves_rent_exempt(
            desired in 0u64..=10_000_000_000u64,
            current_lamports in 0u64..=10_000_000_000u64,
            min_balance in 0u64..=10_000_000_000u64
        ) {
            let capped = cap_at_rent_exempt(desired, current_lamports, min_balance);
            if current_lamports >= min_balance {
                prop_assert!(
                    current_lamports - capped >= min_balance,
                    "deduction {} would drop {} below rent-exempt {}",
                    capped, current_lamports, min_balance
                );
            }
        }
    }
}
