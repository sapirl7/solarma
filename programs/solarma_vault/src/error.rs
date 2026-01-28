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
}
