//! Snooze instruction - reduce deposit for extra time

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct Snooze<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
}

pub fn handler(ctx: Context<Snooze>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // Check deadline not passed
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );
    
    // Calculate snooze cost (10% of remaining, minimum 1 if any balance)
    let cost = alarm.remaining_amount / 10;
    
    require!(
        alarm.remaining_amount >= cost,
        SolarmaError::InsufficientDeposit
    );
    
    // Deduct cost
    alarm.remaining_amount = alarm.remaining_amount
        .checked_sub(cost)
        .ok_or(SolarmaError::Overflow)?;
    
    alarm.snooze_count = alarm.snooze_count
        .checked_add(1)
        .ok_or(SolarmaError::Overflow)?;
    
    msg!("Snooze #{} used, remaining: {}", alarm.snooze_count, alarm.remaining_amount);
    Ok(())
}
