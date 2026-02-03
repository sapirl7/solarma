//! Emergency refund instruction - owner can cancel alarm and get deposit back

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, Vault};
use crate::error::SolarmaError;
use crate::constants::{BURN_SINK, EMERGENCY_REFUND_PENALTY_PERCENT};

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
    
    /// Sink account receives emergency refund penalty
    /// CHECK: Validated against BURN_SINK constant
    #[account(
        mut,
        constraint = sink.key() == BURN_SINK @ SolarmaError::InvalidSinkAddress
    )]
    pub sink: UncheckedAccount<'info>,

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
        .checked_mul(EMERGENCY_REFUND_PENALTY_PERCENT)
        .ok_or(SolarmaError::Overflow)?
        .checked_div(100)
        .ok_or(SolarmaError::Overflow)?;
    
    let refund_amount = alarm.remaining_amount
        .checked_sub(penalty)
        .ok_or(SolarmaError::Overflow)?;
    
    msg!("Emergency refund: {} - {} penalty = {} returned", 
         alarm.remaining_amount, penalty, refund_amount);
    
    if penalty > 0 {
        **ctx.accounts.vault.to_account_info().try_borrow_mut_lamports()? -= penalty;
        **ctx.accounts.sink.to_account_info().try_borrow_mut_lamports()? += penalty;
    }

    // The `close = owner` constraint returns remaining lamports to owner
    
    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;
    
    msg!("Alarm cancelled by owner {}", ctx.accounts.owner.key());
    Ok(())
}
