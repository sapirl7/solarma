//! SweepAcknowledged instruction - permissionlessly close an ACKed alarm after claim grace.

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

    /// Owner receives the swept funds (deposit + rent).
    /// CHECK: Verified by `alarm.has_one(owner)`.
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,

    /// Anyone can trigger a sweep once grace expires.
    pub caller: Signer<'info>,
}

pub fn process_sweep_acknowledged(ctx: Context<SweepAcknowledged>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let caller_key = ctx.accounts.caller.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    let grace_deadline = alarm
        .deadline
        .checked_add(CLAIM_GRACE_SECONDS)
        .ok_or(SolarmaError::Overflow)?;

    // Sweep is only allowed strictly after the grace deadline.
    require!(
        clock.unix_timestamp > grace_deadline,
        SolarmaError::ClaimGraceNotExpired
    );

    let vault_lamports = ctx.accounts.vault.to_account_info().lamports();

    emit!(crate::events::AlarmSwept {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        returned_amount: vault_lamports,
        caller: caller_key,
        timestamp: clock.unix_timestamp,
    });

    // Terminalize with the same status as a normal claim.
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;

    msg!(
        "Swept alarm {} back to owner {} ({} lamports)",
        alarm.alarm_id,
        owner_key,
        vault_lamports
    );

    Ok(())
}
