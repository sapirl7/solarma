//! Snooze instruction - reduce deposit for extra time

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, Vault};
use crate::error::SolarmaError;
use crate::constants::{DEFAULT_SNOOZE_PERCENT, MAX_SNOOZE_COUNT, BURN_SINK};

#[derive(Accounts)]
pub struct Snooze<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,
    
    /// Vault PDA holding the deposit
    #[account(
        mut,
        seeds = [b"vault", alarm.key().as_ref()],
        bump = alarm.vault_bump
    )]
    pub vault: Account<'info, Vault>,
    
    /// Sink account receives snooze penalties
    /// CHECK: This is validated against the BURN_SINK constant
    #[account(
        mut,
        constraint = sink.key() == BURN_SINK @ SolarmaError::InvalidSinkAddress
    )]
    pub sink: UncheckedAccount<'info>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
    
    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<Snooze>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // CRITICAL: Cannot snooze BEFORE alarm time
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );
    
    // Check deadline not passed
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );
    
    // Check snooze limit
    require!(
        alarm.snooze_count < MAX_SNOOZE_COUNT,
        SolarmaError::MaxSnoozesReached
    );
    
    // Calculate snooze cost (exponential: 10% * 2^snooze_count)
    let base_cost = alarm.remaining_amount
        .checked_mul(DEFAULT_SNOOZE_PERCENT)
        .ok_or(SolarmaError::Overflow)?
        .checked_div(100)
        .ok_or(SolarmaError::Overflow)?;
    
    let multiplier = 1u64 << alarm.snooze_count; // 2^snooze_count
    let cost = base_cost
        .checked_mul(multiplier)
        .ok_or(SolarmaError::Overflow)?
        .min(alarm.remaining_amount); // Cap at remaining
    
    require!(cost > 0, SolarmaError::InsufficientDeposit);
    
    // Transfer penalty from vault to sink
    // We need to manually transfer lamports from vault
    if cost > 0 {
        **ctx.accounts.vault.to_account_info().try_borrow_mut_lamports()? -= cost;
        **ctx.accounts.sink.try_borrow_mut_lamports()? += cost;
    }
    
    // Update alarm state
    alarm.remaining_amount = alarm.remaining_amount
        .checked_sub(cost)
        .ok_or(SolarmaError::Overflow)?;
    
    alarm.snooze_count = alarm.snooze_count
        .checked_add(1)
        .ok_or(SolarmaError::Overflow)?;
    
    msg!("Snooze #{}: cost={}, remaining={}", 
         alarm.snooze_count, cost, alarm.remaining_amount);
    Ok(())
}
