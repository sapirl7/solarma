//! Claim instruction - return deposit to user

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct Claim<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
}

pub fn handler(ctx: Context<Claim>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // Check deadline not passed
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );
    
    // Mark as claimed
    alarm.status = AlarmStatus::Claimed;
    
    // TODO: Transfer remaining_amount back to owner if deposit exists
    
    msg!("Alarm claimed successfully");
    Ok(())
}
