//! Claim instruction - return deposit to user before deadline

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
    
    /// Vault PDA holding the deposit
    #[account(
        mut,
        seeds = [b"vault", alarm.key().as_ref()],
        bump = alarm.vault_bump
    )]
    pub vault: SystemAccount<'info>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
    
    pub system_program: Program<'info, System>,
}

pub fn handler(ctx: Context<Claim>) -> Result<()> {
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;
    
    // CRITICAL: Cannot claim after deadline
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );
    
    // Transfer remaining deposit back to owner
    if alarm.remaining_amount > 0 {
        let alarm_key = alarm.key();
        let seeds = &[
            b"vault",
            alarm_key.as_ref(),
            &[alarm.vault_bump],
        ];
        let signer_seeds = &[&seeds[..]];
        
        // Transfer from vault to owner
        let transfer_amount = alarm.remaining_amount;
        **ctx.accounts.vault.try_borrow_mut_lamports()? -= transfer_amount;
        **ctx.accounts.owner.try_borrow_mut_lamports()? += transfer_amount;
        
        msg!("Claimed {} lamports", transfer_amount);
    }
    
    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;
    
    msg!("Alarm claimed successfully by {}", ctx.accounts.owner.key());
    Ok(())
}
