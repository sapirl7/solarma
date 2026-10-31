//! Pure business logic helpers — no Anchor Context dependency.
//!
//! Every public function in this module is testable with `cargo test`.
//! Instruction handlers delegate arithmetic / validation here so that
//! coverage reflects actual domain-level correctness.

use crate::constants::{
    CLAIM_GRACE_SECONDS, DEFAULT_SNOOZE_PERCENT, EMERGENCY_REFUND_PENALTY_PERCENT,
    MAX_SNOOZE_COUNT, MIN_DEPOSIT_LAMPORTS,
};
use crate::state::PenaltyRoute;

// =========================================================================
// Snooze cost arithmetic
// =========================================================================

/// Calculate the raw snooze cost (before rent-exempt capping).
///
/// Formula: `remaining * DEFAULT_SNOOZE_PERCENT / 100 * 2^snooze_count`
///
/// Returns `None` on overflow.
pub fn snooze_cost(remaining_amount: u64, snooze_count: u8) -> Option<u64> {
    let base = remaining_amount
        .checked_mul(DEFAULT_SNOOZE_PERCENT)?
        .checked_div(100)?;

    let multiplier = 1u64.checked_shl(snooze_count as u32)?;
    let cost = base.checked_mul(multiplier)?;
    Some(cost.min(remaining_amount))
}

/// Returns `true` when further snoozes should be blocked.
pub fn is_max_snooze(snooze_count: u8) -> bool {
    snooze_count >= MAX_SNOOZE_COUNT
}

// =========================================================================
// Emergency refund penalty
// =========================================================================

/// Calculate the emergency refund penalty.
///
/// Formula: `remaining * EMERGENCY_REFUND_PENALTY_PERCENT / 100`
///
/// Returns `None` on overflow.
pub fn emergency_penalty(remaining_amount: u64) -> Option<u64> {
    remaining_amount
        .checked_mul(EMERGENCY_REFUND_PENALTY_PERCENT)?
        .checked_div(100)
}

// =========================================================================
// Alarm creation validation (pure)
// =========================================================================

/// Validate alarm parameters without requiring Anchor context.
/// Returns `Ok(())` or a string describing the violation.
pub fn validate_alarm_params(
    alarm_time: i64,
    deadline: i64,
    current_time: i64,
    deposit_amount: u64,
    penalty_route: u8,
    penalty_destination: bool, // whether Some
) -> Result<(), &'static str> {
    if alarm_time <= current_time {
        return Err("alarm_time_in_past");
    }
    if deadline <= alarm_time {
        return Err("invalid_deadline");
    }
    if deposit_amount > 0 && deposit_amount < MIN_DEPOSIT_LAMPORTS {
        return Err("deposit_too_small");
    }
    let route = PenaltyRoute::try_from(penalty_route).map_err(|_| "invalid_penalty_route")?;
    if deposit_amount > 0
        && (route == PenaltyRoute::Donate || route == PenaltyRoute::Buddy)
        && !penalty_destination
    {
        return Err("penalty_destination_required");
    }
    Ok(())
}

// =========================================================================
// Time window validation
// =========================================================================

/// Check whether a claim is within the valid window.
///
/// Valid when `current_time >= alarm_time AND current_time < deadline`.
pub fn is_claim_window(alarm_time: i64, deadline: i64, current_time: i64) -> bool {
    current_time >= alarm_time && current_time < deadline
}

/// Compute claim deadline including post-deadline claim grace.
pub fn claim_deadline_with_grace(deadline: i64) -> Option<i64> {
    deadline.checked_add(CLAIM_GRACE_SECONDS)
}

/// Check whether claim is valid for acknowledged alarms.
///
/// Valid when `current_time >= alarm_time AND current_time <= deadline + CLAIM_GRACE_SECONDS`.
pub fn is_claim_window_with_grace(alarm_time: i64, deadline: i64, current_time: i64) -> bool {
    if current_time < alarm_time {
        return false;
    }
    let Some(claim_deadline) = claim_deadline_with_grace(deadline) else {
        return false;
    };
    current_time <= claim_deadline
}

/// Check whether sweep is valid for acknowledged alarms.
///
/// Valid only strictly after claim grace expires:
/// `current_time > deadline + CLAIM_GRACE_SECONDS`.
pub fn is_sweep_window(deadline: i64, current_time: i64) -> bool {
    let Some(claim_deadline) = claim_deadline_with_grace(deadline) else {
        return false;
    };
    current_time > claim_deadline
}

/// Check whether a slash is valid (after deadline).
pub fn is_slash_window(deadline: i64, current_time: i64) -> bool {
    current_time >= deadline
}

/// Check whether an emergency refund is valid (before alarm time).
pub fn is_refund_window(alarm_time: i64, current_time: i64) -> bool {
    current_time < alarm_time
}

/// Check whether a snooze is valid (after alarm_time, before deadline).
pub fn is_snooze_window(alarm_time: i64, deadline: i64, current_time: i64) -> bool {
    current_time >= alarm_time && current_time < deadline
}

// =========================================================================
// Penalty routing
// =========================================================================

/// Validate the penalty recipient address matches the expected target.
///
/// For Burn route → must match BURN_SINK.
/// For Donate/Buddy → must match `penalty_destination`.
pub fn validate_penalty_recipient(
    route: u8,
    recipient: &[u8; 32],
    burn_sink: &[u8; 32],
    penalty_destination: Option<&[u8; 32]>,
) -> Result<(), &'static str> {
    let parsed = PenaltyRoute::try_from(route).map_err(|_| "invalid_penalty_route")?;
    match parsed {
        PenaltyRoute::Burn => {
            if recipient != burn_sink {
                return Err("invalid_penalty_recipient");
            }
        }
        PenaltyRoute::Donate | PenaltyRoute::Buddy => {
            let dest = penalty_destination.ok_or("penalty_destination_not_set")?;
            if recipient != dest {
                return Err("invalid_penalty_recipient");
            }
        }
    }
    Ok(())
}

// =========================================================================
// Snooze time extension
// =========================================================================

/// Calculate new alarm_time and deadline after a snooze.
///
/// Returns `(new_alarm_time, new_deadline)` or `None` on overflow.
pub fn snooze_time_extension(
    alarm_time: i64,
    deadline: i64,
    extension_seconds: i64,
) -> Option<(i64, i64)> {
    let new_alarm = alarm_time.checked_add(extension_seconds)?;
    let new_deadline = deadline.checked_add(extension_seconds)?;
    Some((new_alarm, new_deadline))
}

// =========================================================================
// Rent-exempt capping
// =========================================================================

/// Cap a deduction at what's available above rent-exempt minimum.
///
/// Returns the actual deductible amount.
pub fn cap_at_rent_exempt(desired: u64, current_lamports: u64, min_balance: u64) -> u64 {
    let available = current_lamports.saturating_sub(min_balance);
    desired.min(available)
}

// =========================================================================
// Deadline horizon
// =========================================================================

/// Check whether `deadline` is within `max_horizon` seconds of `current_time`.
///
/// Bounds the deadline so that downstream `deadline + grace` additions in
/// claim/sweep cannot overflow `i64` and permanently lock a vault. Returns
/// `false` when `current_time + max_horizon` would itself overflow.
pub fn deadline_within_horizon(deadline: i64, current_time: i64, max_horizon: i64) -> bool {
    match current_time.checked_add(max_horizon) {
        Some(max_deadline) => deadline <= max_deadline,
        None => false,
    }
}

#[cfg(test)]
mod horizon_tests {
    use super::deadline_within_horizon;

    #[test]
    fn accepts_deadline_within_horizon() {
        assert!(deadline_within_horizon(1_000 + 500, 1_000, 1_000));
        assert!(deadline_within_horizon(1_000 + 1_000, 1_000, 1_000)); // inclusive
    }

    #[test]
    fn rejects_deadline_beyond_horizon() {
        assert!(!deadline_within_horizon(1_000 + 1_001, 1_000, 1_000));
    }

    #[test]
    fn rejects_on_overflow() {
        // current_time + max_horizon overflows i64 -> cannot bound safely -> reject.
        assert!(!deadline_within_horizon(i64::MAX, i64::MAX - 5, 100));
    }
}

// =========================================================================
// Penalty destination
// =========================================================================

/// Check that a Donate/Buddy `penalty_destination` can actually receive a slash.
///
/// Rejects destinations that would make the penalty meaningless (the owner)
/// or make `slash` permanently fail and freeze the deposit: the alarm or vault
/// PDA itself (closing the vault into itself burns the lamports and the runtime
/// rejects the transaction), and executable accounts we know about (System
/// program, this program), whose balance cannot be credited.
pub fn is_valid_penalty_destination(
    destination: &[u8; 32],
    owner: &[u8; 32],
    alarm: &[u8; 32],
    vault: &[u8; 32],
    program_id: &[u8; 32],
) -> bool {
    const SYSTEM_PROGRAM: [u8; 32] = [0u8; 32];
    destination != owner
        && destination != alarm
        && destination != vault
        && destination != program_id
        && *destination != SYSTEM_PROGRAM
}

#[cfg(test)]
mod penalty_destination_tests {
    use super::is_valid_penalty_destination;

    const OWNER: [u8; 32] = [1u8; 32];
    const ALARM: [u8; 32] = [2u8; 32];
    const VAULT: [u8; 32] = [3u8; 32];
    const PROGRAM: [u8; 32] = [4u8; 32];

    fn check(dest: [u8; 32]) -> bool {
        is_valid_penalty_destination(&dest, &OWNER, &ALARM, &VAULT, &PROGRAM)
    }

    #[test]
    fn accepts_third_party_destination() {
        assert!(check([9u8; 32]));
    }

    #[test]
    fn rejects_owner_alarm_vault_and_programs() {
        assert!(!check(OWNER));
        assert!(!check(ALARM));
        assert!(!check(VAULT));
        assert!(!check(PROGRAM));
        assert!(!check([0u8; 32]));
    }
}

// =========================================================================
// Ack authority
// =========================================================================

/// Whether `signer` may acknowledge (and claim) an alarm: the owner, or the
/// alarm's ack authority if one is set (all-zero = none).
pub fn is_ack_signer(signer: &[u8; 32], owner: &[u8; 32], ack_authority: &[u8; 32]) -> bool {
    signer == owner || (*ack_authority != [0u8; 32] && signer == ack_authority)
}

#[cfg(test)]
mod ack_signer_tests {
    use super::is_ack_signer;

    const OWNER: [u8; 32] = [1u8; 32];
    const DEVICE: [u8; 32] = [2u8; 32];
    const OTHER: [u8; 32] = [3u8; 32];
    const NONE: [u8; 32] = [0u8; 32];

    #[test]
    fn owner_can_always_ack() {
        assert!(is_ack_signer(&OWNER, &OWNER, &NONE));
        assert!(is_ack_signer(&OWNER, &OWNER, &DEVICE));
    }

    #[test]
    fn ack_authority_can_ack_when_set() {
        assert!(is_ack_signer(&DEVICE, &OWNER, &DEVICE));
    }

    #[test]
    fn others_and_unset_authority_cannot_ack() {
        assert!(!is_ack_signer(&OTHER, &OWNER, &DEVICE));
        // An all-zero signer never matches an unset authority
        assert!(!is_ack_signer(&NONE, &OWNER, &NONE));
    }
}
