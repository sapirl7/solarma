//! Program constants

use anchor_lang::prelude::*;

/// Sink address for burned deposits (a well-known burn address pattern)
/// In production, this should be a proper burn address or configured via program data
pub const BURN_SINK: Pubkey = Pubkey::new_from_array([
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
]);

/// Default snooze cost percentage (10% of remaining)
pub const DEFAULT_SNOOZE_PERCENT: u64 = 10;

/// Maximum snooze count before full penalty
pub const MAX_SNOOZE_COUNT: u8 = 10;

/// Minimum deposit amount in lamports (0.001 SOL)
pub const MIN_DEPOSIT_LAMPORTS: u64 = 1_000_000;

/// Grace period after alarm time before deadline starts (in seconds)
/// Default: 30 minutes = 1800 seconds
pub const DEFAULT_GRACE_PERIOD: i64 = 1800;
