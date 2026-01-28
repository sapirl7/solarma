//! Program error definitions

use anchor_lang::prelude::*;

#[error_code]
pub enum SolarmaError {
    #[msg("Alarm deadline has passed")]
    DeadlinePassed,

    #[msg("Alarm deadline has not passed yet")]
    DeadlineNotPassed,

    #[msg("Alarm is not in a valid state for this operation")]
    InvalidAlarmState,

    #[msg("Invalid penalty route")]
    InvalidPenaltyRoute,

    #[msg("Insufficient deposit remaining")]
    InsufficientDeposit,

    #[msg("Arithmetic overflow")]
    Overflow,

    #[msg("Alarm time must be in the future")]
    AlarmTimeInPast,

    #[msg("Deadline must be after alarm time")]
    InvalidDeadline,

    #[msg("Deposit amount too small (minimum 0.001 SOL)")]
    DepositTooSmall,

    #[msg("Buddy penalty route requires destination address")]
    BuddyAddressRequired,

    #[msg("Invalid sink address for snooze penalties")]
    InvalidSinkAddress,

    #[msg("Maximum snooze count reached")]
    MaxSnoozesReached,

    #[msg("Invalid penalty recipient address")]
    InvalidPenaltyRecipient,

    #[msg("Penalty destination not set for this route")]
    PenaltyDestinationNotSet,

    #[msg("Cannot perform operation before alarm time")]
    TooEarly,
}
