//! Program state definitions

use anchor_lang::prelude::*;

/// Status of an alarm
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum AlarmStatus {
    #[default]
    Created,
    /// H3: Owner has acknowledged wake proof on-chain
    Acknowledged,
    Claimed,
    Slashed,
}

/// Penalty route for failed alarms
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, Debug)]
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
    pub const SIZE: usize = 8  // discriminator
        + 32  // owner
        + 1 + 32  // Option<[u8; 32]>
        + 1; // bump
}

/// Alarm PDA
#[account]
#[derive(Default)]
pub struct Alarm {
    /// Owner of this alarm
    pub owner: Pubkey,
    /// Client-assigned alarm identifier (used in PDA seeds)
    pub alarm_id: u64,
    /// Scheduled alarm time (Unix timestamp)
    pub alarm_time: i64,
    /// Deadline for claiming (Unix timestamp)
    pub deadline: i64,
    /// Initial deposit amount
    pub initial_amount: u64,
    /// Remaining deposit amount
    pub remaining_amount: u64,
    /// Penalty route (0=Burn, 1=Donate, 2=Buddy)
    pub penalty_route: u8,
    /// Penalty destination address (for Donate/Buddy)
    pub penalty_destination: Option<Pubkey>,
    /// Number of snoozes used
    pub snooze_count: u8,
    /// Current status
    pub status: AlarmStatus,
    /// Bump seed for alarm PDA
    pub bump: u8,
    /// Bump seed for vault PDA
    pub vault_bump: u8,
    /// Optional key (typically generated on the owner's device) that may sign
    /// `ack_and_claim` on the owner's behalf. `Pubkey::default()` = none.
    /// It can only return the deposit to the owner, so a leaked key can't move funds elsewhere.
    pub ack_authority: Pubkey,
}

impl Alarm {
    pub const SIZE: usize = 8  // discriminator
        + 32  // owner
        + 8   // alarm_id
        + 8   // alarm_time
        + 8   // deadline
        + 8   // initial_amount
        + 8   // remaining_amount
        + 1   // penalty_route
        + 1 + 32  // Option<Pubkey> penalty_destination
        + 1   // snooze_count
        + 1   // status
        + 1   // bump
        + 1   // vault_bump
        + 32  // ack_authority
        + 32; // padding for future fields (e.g. deposit_mint)

    /// Whether `signer` may acknowledge and claim this alarm.
    pub fn can_ack(&self, signer: &Pubkey) -> bool {
        crate::helpers::is_ack_signer(
            &signer.to_bytes(),
            &self.owner.to_bytes(),
            &self.ack_authority.to_bytes(),
        )
    }
}

/// Global configuration (single PDA at `[CONFIG_SEED]`).
#[account]
pub struct Config {
    /// May pause the program and change limits (intended: a multisig)
    pub admin: Pubkey,
    /// When true, no new alarms can be created. Existing alarms can always be resolved.
    pub paused: bool,
    /// Maximum deposit per alarm in lamports
    pub max_deposit_lamports: u64,
    /// Bump seed for PDA
    pub bump: u8,
}

impl Config {
    pub const SIZE: usize = 8  // discriminator
        + 32  // admin
        + 1   // paused
        + 8   // max_deposit_lamports
        + 1   // bump
        + 32; // padding
}

/// Vault PDA - holds deposited SOL for an alarm
#[account]
pub struct Vault {
    /// Associated alarm pubkey
    pub alarm: Pubkey,
    /// Bump seed for PDA derivation
    pub bump: u8,
}

impl Vault {
    pub const SIZE: usize = 8   // discriminator
        + 32  // alarm
        + 1; // bump
}
