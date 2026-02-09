//! AckAwake instruction - record wake proof completion on-chain (H3)
//!
//! This instruction is called by the owner after completing the wake proof
//! on the client side. It transitions the alarm from Created → Acknowledged,
//! providing on-chain evidence that the user woke up. This reduces the race
//! window between claim and slash, and prevents bots from slashing before
//! the claim transaction reaches finality.

use crate::error::SolarmaError;
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

pub fn process_ack_awake(ctx: Context<AckAwake>) -> Result<()> {
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
