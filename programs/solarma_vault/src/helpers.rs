//! Pure business logic helpers — no Anchor Context dependency.
//!
//! Every public function in this module is testable with `cargo test`.
//! Instruction handlers delegate arithmetic / validation here so that
//! coverage reflects actual domain-level correctness.

use crate::constants::{
    BUDDY_ONLY_SECONDS, CLAIM_GRACE_SECONDS, DEFAULT_SNOOZE_PERCENT,
    EMERGENCY_REFUND_PENALTY_PERCENT, MAX_SNOOZE_COUNT, MIN_DEPOSIT_LAMPORTS,
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

/// Check whether current time falls into buddy-only slash subwindow.
///
/// Valid for `deadline <= current_time < deadline + BUDDY_ONLY_SECONDS`.
pub fn is_buddy_only_window(deadline: i64, current_time: i64) -> bool {
    if current_time < deadline {
        return false;
    }
    let Some(buddy_only_end) = deadline.checked_add(BUDDY_ONLY_SECONDS) else {
        return false;
    };
    current_time < buddy_only_end
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
// Wake proof (commitment / reveal)
// =========================================================================

/// The all-zero commitment: "no wake proof required" (None mode / zero-stake).
pub const NO_WAKE_PROOF: [u8; 32] = [0u8; 32];

/// Compute the wake-proof commitment stored at alarm creation:
/// `blake3(preimage ‖ owner ‖ alarm_id_le)`.
///
/// Binding it to `owner` + `alarm_id` lets the same physical secret (an NFC tag
/// or QR code) back multiple alarms without cross-alarm replay: each alarm's
/// commitment is distinct even for an identical `preimage`.
pub fn wake_commitment(preimage: &[u8; 32], owner: &[u8; 32], alarm_id: u64) -> [u8; 32] {
    let mut buf = [0u8; 72]; // 32 (preimage) + 32 (owner) + 8 (alarm_id)
    buf[..32].copy_from_slice(preimage);
    buf[32..64].copy_from_slice(owner);
    buf[64..].copy_from_slice(&alarm_id.to_le_bytes());
    *blake3::hash(&buf).as_bytes()
}

/// Verify a revealed `preimage` against the stored `commitment`.
///
/// An all-zero `commitment` (`NO_WAKE_PROOF`) means no proof is required and
/// always verifies. sha256 can never output all-zero, so a real commitment can
/// never collide with the sentinel.
pub fn verify_wake_proof(
    preimage: &[u8; 32],
    owner: &[u8; 32],
    alarm_id: u64,
    commitment: &[u8; 32],
) -> bool {
    if commitment == &NO_WAKE_PROOF {
        return true;
    }
    &wake_commitment(preimage, owner, alarm_id) == commitment
}

#[cfg(test)]
mod wake_proof_tests {
    use super::{verify_wake_proof, wake_commitment, NO_WAKE_PROOF};

    #[test]
    fn correct_preimage_verifies() {
        let preimage = [7u8; 32];
        let owner = [9u8; 32];
        let c = wake_commitment(&preimage, &owner, 42);
        assert_ne!(c, NO_WAKE_PROOF); // sha256 never all-zero
        assert!(verify_wake_proof(&preimage, &owner, 42, &c));
    }

    #[test]
    fn wrong_preimage_rejected() {
        let owner = [9u8; 32];
        let c = wake_commitment(&[7u8; 32], &owner, 42);
        assert!(!verify_wake_proof(&[8u8; 32], &owner, 42, &c));
    }

    #[test]
    fn bound_to_owner_and_alarm_id() {
        let preimage = [7u8; 32];
        let c = wake_commitment(&preimage, &[9u8; 32], 42);
        // same preimage, different owner -> rejected
        assert!(!verify_wake_proof(&preimage, &[1u8; 32], 42, &c));
        // same preimage + owner, different alarm_id -> rejected
        assert!(!verify_wake_proof(&preimage, &[9u8; 32], 43, &c));
    }

    #[test]
    fn zero_commitment_needs_no_proof() {
        assert!(verify_wake_proof(&[0u8; 32], &[0u8; 32], 0, &NO_WAKE_PROOF));
        assert!(verify_wake_proof(
            &[123u8; 32],
            &[1u8; 32],
            5,
            &NO_WAKE_PROOF
        ));
    }
}
