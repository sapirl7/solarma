//! AckAndClaim instruction - acknowledge the wake-up and return the deposit in one step.
//!
//! Signable by the owner or by the alarm's `ack_authority` (a key kept on the
//! owner's device), so the alarm screen can settle the deposit without opening a
//! wallet. Funds and rent can only go to the owner, so the ack authority can't
//! redirect anything.

use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct AckAndClaim<'info> {
    /// Closed on success: its rent goes back to the owner.
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState,
        constraint = alarm.can_ack(&signer.key()) @ SolarmaError::Unauthorized,
        close = owner
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

    /// Alarm owner, receives the deposit and both accounts' rent.
    /// CHECK: Key is verified by `alarm.has_one = owner`
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,

    /// The owner or the alarm's ack authority
    pub signer: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_ack_and_claim(ctx: Context<AckAndClaim>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &ctx.accounts.alarm;
    let clock = Clock::get()?;

    // Same window as ack_awake: after the alarm rang, before the deadline
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );

    let vault_lamports = ctx.accounts.vault.to_account_info().lamports();

    emit!(crate::events::WakeAcknowledged {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        timestamp: clock.unix_timestamp,
    });
    emit!(crate::events::AlarmClaimed {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        returned_amount: vault_lamports,
    });

    // Terminal: alarm and vault are both closed to the owner by their `close` constraints.
    msg!(
        "Alarm acknowledged and claimed by {}: {} lamports returned to {}",
        ctx.accounts.signer.key(),
        vault_lamports,
        owner_key
    );
    Ok(())
}
