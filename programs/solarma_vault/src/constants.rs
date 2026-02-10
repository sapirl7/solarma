//! Program constants

use anchor_lang::prelude::*;

/// Sink address for burned deposits (Solana incinerator)
/// https://explorer.solana.com/address/1nc1nerator11111111111111111111111111111111
pub const BURN_SINK: Pubkey = Pubkey::new_from_array([
    0, 51, 144, 114, 141, 52, 17, 96, 121, 189, 201, 17, 191, 255, 0, 219, 212, 77, 46, 205, 204,
    247, 156, 166, 225, 0, 56, 225, 0, 0, 0, 0,
]);

/// Default snooze cost percentage (10% of remaining)
pub const DEFAULT_SNOOZE_PERCENT: u64 = 10;

/// Maximum snooze count before full penalty
pub const MAX_SNOOZE_COUNT: u8 = 10;

/// Minimum deposit amount in lamports (0.001 SOL)
pub const MIN_DEPOSIT_LAMPORTS: u64 = 1_000_000;

/// Emergency refund penalty percent (e.g., 5%)
pub const EMERGENCY_REFUND_PENALTY_PERCENT: u64 = 5;

/// Grace period after alarm time before deadline starts (in seconds)
/// Default: 30 minutes = 1800 seconds
pub const DEFAULT_GRACE_PERIOD: i64 = 1800;

/// Default snooze extension (in seconds)
/// Default: 5 minutes = 300 seconds
pub const DEFAULT_SNOOZE_EXTENSION_SECONDS: i64 = 300;

/// Claim grace window after `alarm.deadline` (in seconds).
///
/// If the owner ACKed in time, they may still claim up to `deadline + CLAIM_GRACE_SECONDS`.
pub const CLAIM_GRACE_SECONDS: i64 = 120;

/// Buddy-only slash window after `alarm.deadline` (in seconds).
///
/// Only applies when `penalty_route == Buddy`. During this window, only the buddy
/// (the configured `penalty_destination`) may call `slash`. After it expires,
/// slash becomes permissionless again.
pub const BUDDY_ONLY_SECONDS: i64 = 120;

// ============================================================================
// Attestation (optional)
// ============================================================================

/// Expected cluster label embedded in attestation permits.
///
/// This is an application-level value, not an on-chain sysvar. Update it when
/// deploying the same program id to a different cluster.
pub const ATTESTATION_CLUSTER: &str = "devnet";

/// Domain separator for signed permits.
pub const ATTESTATION_DOMAIN: &str = "solarma";

/// Permit action name for ACK.
pub const ATTESTATION_ACTION_ACK: &str = "ack";

/// Expected Ed25519 public key of the attestation signer.
///
/// For local development/tests this is a deterministic key. Before production
/// deployment, replace with your real server key and rotate via redeploy.
pub const ATTESTATION_PUBKEY: Pubkey = Pubkey::new_from_array([
    25, 127, 107, 35, 225, 108, 133, 50, 198, 171, 200, 56, 250, 205, 94, 167, 137, 190, 12, 118,
    178, 146, 3, 52, 3, 155, 250, 139, 61, 54, 141, 97,
]);
