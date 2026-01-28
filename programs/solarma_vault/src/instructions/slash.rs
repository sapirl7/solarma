//! Slash instruction - transfer deposit after deadline (permissionless)

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct Slash<'info> {
    #[account(
        mut,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,
    
    /// Anyone can trigger slash after deadline
    pub caller: Signer<'info>,
}

pub fn handler(ctx: Context<Slash>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // Check deadline HAS passed
    require!(
        clock.unix_timestamp >= alarm.deadline,
        SolarmaError::DeadlineNotPassed
    );
    
    // Mark as slashed
    alarm.status = AlarmStatus::Slashed;
    
    // TODO: Transfer remaining_amount to penalty destination based on penalty_route
    
    msg!("Alarm slashed, deposit forfeited");
    Ok(())
}
