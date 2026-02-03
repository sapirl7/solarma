//! Claim instruction - return deposit to user AFTER alarm_time but BEFORE deadline

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, Vault};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct Claim<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,
    
    /// Vault PDA holding the deposit - closed and funds returned to owner
    #[account(
        mut,
        seeds = [b"vault", alarm.key().as_ref()],
        bump = alarm.vault_bump,
        close = owner
    )]
    pub vault: Account<'info, Vault>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
    
    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<Claim>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // CRITICAL: Cannot claim BEFORE alarm time (wake proof not complete)
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );
    
    // CRITICAL: Cannot claim AFTER deadline
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );
    
    // The `close = owner` constraint automatically transfers all lamports
    // (rent + remaining deposit) back to owner when vault account is closed
    
    msg!("Claimed {} lamports back to owner", alarm.remaining_amount);
    
    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;
    
    msg!("Alarm claimed successfully by {}", ctx.accounts.owner.key());
    Ok(())
}
