//! Program state definitions

use anchor_lang::prelude::*;

/// Status of an alarm
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq)]
pub enum AlarmStatus {
    Created,
    Claimed,
    Slashed,
}

impl Default for AlarmStatus {
    fn default() -> Self {
        AlarmStatus::Created
    }
}

/// Penalty route for failed alarms
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq)]
pub enum PenaltyRoute {
    Burn,   // Send to sink address
    Donate, // Send to charity
    Buddy,  // Send to friend
}

impl TryFrom<u8> for PenaltyRoute {
    type Error = ();

    fn try_from(value: u8) -> std::result::Result<Self, Self::Error> {
        match value {
            0 => Ok(PenaltyRoute::Burn),
            1 => Ok(PenaltyRoute::Donate),
            2 => Ok(PenaltyRoute::Buddy),
            _ => Err(()),
        }
    }
}

/// User profile PDA
#[account]
#[derive(Default)]
pub struct UserProfile {
    /// Owner of this profile
    pub owner: Pubkey,
    /// Optional registered NFC/QR tag hash
    pub tag_hash: Option<[u8; 32]>,
    /// Bump seed for PDA
    pub bump: u8,
}

impl UserProfile {
    pub const SIZE: usize = 8 + 32 + 1 + 32 + 1;
}

/// Alarm PDA
#[account]
#[derive(Default)]
pub struct Alarm {
    /// Owner of this alarm
    pub owner: Pubkey,
    /// Scheduled alarm time (Unix timestamp)
    pub alarm_time: i64,
    /// Deadline for claiming (Unix timestamp)
    pub deadline: i64,
    /// Token mint for deposit (None = SOL)
    pub deposit_mint: Option<Pubkey>,
    /// Initial deposit amount
    pub initial_amount: u64,
    /// Remaining deposit amount
    pub remaining_amount: u64,
    /// Penalty route
    pub penalty_route: u8,
    /// Penalty destination address
    pub penalty_destination: Option<Pubkey>,
    /// Number of snoozes used
    pub snooze_count: u8,
    /// Current status
    pub status: AlarmStatus,
    /// Bump seed for PDA
    pub bump: u8,
}

impl Alarm {
    pub const SIZE: usize = 8 + 32 + 8 + 8 + 1 + 32 + 8 + 8 + 1 + 1 + 32 + 1 + 1 + 1 + 16; // padding
}
