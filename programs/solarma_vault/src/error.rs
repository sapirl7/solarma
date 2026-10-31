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

    #[msg("Donate or Buddy route requires destination address")]
    PenaltyDestinationRequired,

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

    #[msg("Cannot request refund after alarm time has passed")]
    TooLateForRefund,

    /// Unused since the buddy-only slash window was removed; kept so that the
    /// codes of the variants below don't shift.
    #[msg("Only buddy can slash during buddy-only window")]
    BuddyOnlyWindow,

    #[msg("Deadline is too far in the future (exceeds the max alarm horizon)")]
    DeadlineTooFar,

    #[msg("Penalty destination cannot be the owner, the alarm/vault accounts, or a program")]
    InvalidPenaltyDestination,

    #[msg("Program is paused: new alarms can't be created right now")]
    ProgramPaused,

    #[msg("Deposit exceeds the current maximum")]
    DepositTooLarge,

    #[msg("Signer is not authorized for this operation")]
    Unauthorized,
}
