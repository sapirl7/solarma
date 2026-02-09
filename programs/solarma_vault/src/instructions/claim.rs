//! Claim instruction - return deposit to user AFTER alarm_time but BEFORE deadline

use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct Claim<'info> {
    #[account(
        mut,
        has_one = owner,
        // H3: Allow claim from both Created and Acknowledged states
        constraint = (alarm.status == AlarmStatus::Created || alarm.status == AlarmStatus::Acknowledged) @ SolarmaError::InvalidAlarmState
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

pub fn process_claim(ctx: Context<Claim>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
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
    let vault_lamports = ctx.accounts.vault.to_account_info().lamports();

    emit!(crate::events::AlarmClaimed {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        returned_amount: vault_lamports,
    });

    msg!(
        "Claimed {} lamports back to owner (deposit + rent)",
        vault_lamports
    );

    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;

    msg!("Alarm claimed successfully by {}", owner_key);
    Ok(())
}
