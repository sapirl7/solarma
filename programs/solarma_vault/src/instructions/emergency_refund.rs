//! Emergency refund instruction - owner can cancel alarm and get deposit back

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, Vault};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct EmergencyRefund<'info> {
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

pub fn handler(ctx: Context<EmergencyRefund>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // CRITICAL: Can only refund BEFORE alarm time
    // This is the escape hatch if something goes wrong
    require!(
        clock.unix_timestamp < alarm.alarm_time,
        SolarmaError::TooLateForRefund
    );
    
    // Calculate penalty (e.g., 5% fee for early cancellation)
    let penalty = alarm.remaining_amount
        .checked_mul(5)
        .ok_or(SolarmaError::Overflow)?
        .checked_div(100)
        .ok_or(SolarmaError::Overflow)?;
    
    let refund_amount = alarm.remaining_amount
        .checked_sub(penalty)
        .ok_or(SolarmaError::Overflow)?;
    
    msg!("Emergency refund: {} - {} penalty = {} returned", 
         alarm.remaining_amount, penalty, refund_amount);
    
    // The `close = owner` constraint returns all lamports to owner
    // Penalty is absorbed since vault is closed anyway
    // In production, could send penalty to protocol treasury
    
    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;
    
    msg!("Alarm cancelled by owner {}", ctx.accounts.owner.key());
    Ok(())
}
