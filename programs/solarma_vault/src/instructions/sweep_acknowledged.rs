//! Sweep acknowledged instruction - permissionless owner return after claim grace.

use crate::constants::CLAIM_GRACE_SECONDS;
use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct SweepAcknowledged<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Acknowledged @ SolarmaError::InvalidAlarmState
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

    /// Alarm owner account, validated via `has_one = owner`
    /// CHECK: Key is verified by `alarm.has_one = owner`
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,

    /// Any signer can trigger sweep after grace window
    pub caller: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_sweep_acknowledged(ctx: Context<SweepAcknowledged>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let caller_key = ctx.accounts.caller.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    let claim_deadline = alarm
        .deadline
        .checked_add(CLAIM_GRACE_SECONDS)
        .ok_or(SolarmaError::Overflow)?;

    // Sweep is only allowed strictly after claim grace has expired.
    require!(
        clock.unix_timestamp > claim_deadline,
        SolarmaError::DeadlineNotPassed
    );

    // The `close = owner` constraint automatically transfers all lamports
    // (rent + remaining deposit) back to owner when vault account is closed.
    let vault_lamports = ctx.accounts.vault.to_account_info().lamports();

    emit!(crate::events::AlarmClaimed {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        returned_amount: vault_lamports,
    });

    msg!(
        "Sweep acknowledged by {}: returned {} lamports to owner {}",
        caller_key,
        vault_lamports,
        owner_key
    );

    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;

    Ok(())
}
