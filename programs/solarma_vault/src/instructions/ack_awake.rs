//! AckAwake instruction - verify the wake proof and acknowledge on-chain (H3)
//!
//! The owner reveals the `wake_preimage` committed at create time. The program
//! recomputes `blake3(preimage ‖ owner ‖ alarm_id)` and checks it against the
//! stored `wake_commitment`; only then does it transition Created → Acknowledged.
//! A zero commitment (None mode / zero-stake) requires no proof. This makes the
//! deposit genuinely at risk — reclaiming it requires revealing the secret — and
//! reduces the race window between claim and slash.

use crate::error::SolarmaError;
use crate::helpers;
use crate::state::{Alarm, AlarmStatus};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct AckAwake<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,

    #[account(mut)]
    pub owner: Signer<'info>,
}

pub fn process_ack_awake(ctx: Context<AckAwake>, wake_preimage: [u8; 32]) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    // Can only acknowledge after alarm time (i.e., alarm has fired)
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );

    // Can only acknowledge before deadline
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );

    // Verify the wake proof: the owner reveals the preimage committed at create
    // time (a zero commitment = None mode requires no proof).
    require!(
        helpers::verify_wake_proof(
            &wake_preimage,
            &owner_key.to_bytes(),
            alarm.alarm_id,
            &alarm.wake_commitment,
        ),
        SolarmaError::InvalidWakeProof
    );

    // Transition to Acknowledged
    alarm.status = AlarmStatus::Acknowledged;

    emit!(crate::events::WakeAcknowledged {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        timestamp: clock.unix_timestamp,
    });

    msg!(
        "Alarm acknowledged by {} at timestamp {}",
        owner_key,
        clock.unix_timestamp
    );
    Ok(())
}
