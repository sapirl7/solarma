//! Pure business logic helpers — no Anchor Context dependency.
//!
//! Every public function in this module is testable with `cargo test`.
//! Instruction handlers delegate arithmetic / validation here so that
//! coverage reflects actual domain-level correctness.

use crate::constants::{
    DEFAULT_SNOOZE_PERCENT, EMERGENCY_REFUND_PENALTY_PERCENT, MAX_SNOOZE_COUNT,
    MIN_DEPOSIT_LAMPORTS,
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
pub fn cap_at_rent_exempt(
    desired: u64,
    current_lamports: u64,
    min_balance: u64,
) -> u64 {
    let available = current_lamports.saturating_sub(min_balance);
    desired.min(available)
}
