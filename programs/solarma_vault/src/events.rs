//! Program events for indexer visibility
//!
//! Every instruction emits a structured event so that off-chain
//! indexers (Helius, Triton, custom gPA subscribers) can track
//! the full alarm lifecycle without parsing account data.
//!
//! All alarm-related events include `alarm_id` for client-side correlation.

use anchor_lang::prelude::*;

/// Emitted when a user profile is initialized
#[event]
pub struct ProfileInitialized {
    pub owner: Pubkey,
}

/// Emitted when a new alarm + vault is created
#[event]
pub struct AlarmCreated {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub alarm_time: i64,
    pub deadline: i64,
    pub deposit_amount: u64,
    pub penalty_route: u8,
}

/// Emitted when an alarm is successfully claimed
#[event]
pub struct AlarmClaimed {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub returned_amount: u64,
}

/// Emitted when an alarm is snoozed
#[event]
pub struct AlarmSnoozed {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub snooze_count: u8,
    pub cost: u64,
    pub remaining: u64,
    pub new_alarm_time: i64,
    pub new_deadline: i64,
}

/// Emitted when an alarm is slashed after deadline
#[event]
pub struct AlarmSlashed {
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub penalty_recipient: Pubkey,
    pub slashed_amount: u64,
    pub caller: Pubkey,
}

/// Emitted when an emergency refund is executed
#[event]
pub struct EmergencyRefundExecuted {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub penalty_amount: u64,
    /// Total lamports returned to owner (deposit - penalty + rent)
    pub returned_amount: u64,
}

/// Emitted when a wake proof is acknowledged on-chain (H3)
#[event]
pub struct WakeAcknowledged {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub timestamp: i64,
}

/// Emitted when an acknowledged alarm is swept after the claim grace window.
#[event]
pub struct AlarmSwept {
    pub owner: Pubkey,
    pub alarm: Pubkey,
    pub alarm_id: u64,
    pub returned_amount: u64,
    pub caller: Pubkey,
    pub timestamp: i64,
}
