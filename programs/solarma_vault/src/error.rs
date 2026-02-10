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

    #[msg("Claim grace window has expired")]
    ClaimGraceExpired,

    #[msg("Claim grace window has not expired yet")]
    ClaimGraceNotExpired,

    #[msg("Buddy-only slash window active: only the buddy may slash")]
    BuddyOnlySlashWindow,

    // ---------------------------------------------------------------------
    // Attestation (optional)
    // ---------------------------------------------------------------------
    #[msg("Permit expired")]
    PermitExpired,

    #[msg("Missing Ed25519 verify instruction")]
    MissingEd25519Verify,

    #[msg("Invalid Ed25519 verify instruction")]
    InvalidEd25519Verify,

    #[msg("Attestation pubkey mismatch")]
    AttestationPubkeyMismatch,

    #[msg("Permit message mismatch")]
    InvalidPermitMessage,
}
