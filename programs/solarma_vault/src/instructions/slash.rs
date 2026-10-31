//! Slash instruction - transfer deposit after deadline (permissionless)

use crate::constants::BURN_SINK;
use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct Slash<'info> {
    /// Closed on success: its rent goes back to the owner (only the deposit is the penalty).
    #[account(
        mut,
        has_one = owner,
        // Slash is only possible while alarm is still unresolved (Created).
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState,
        close = owner
    )]
    pub alarm: Account<'info, Alarm>,

    /// Vault PDA holding the deposit - closed and funds transferred to penalty_recipient
    #[account(
        mut,
        seeds = [b"vault", alarm.key().as_ref()],
        bump = alarm.vault_bump,
        close = penalty_recipient
    )]
    pub vault: Account<'info, Vault>,

    /// Penalty destination - varies based on route
    /// CHECK: Validated against alarm.penalty_destination or BURN_SINK
    #[account(mut)]
    pub penalty_recipient: UncheckedAccount<'info>,

    /// Anyone can trigger slash after deadline
    pub caller: Signer<'info>,

    pub system_program: Program<'info, System>,

    /// Alarm owner, receives the alarm account's rent. Validated via `has_one = owner`.
    /// Appended last so existing account positions are unchanged.
    /// CHECK: Key is verified by `alarm.has_one = owner`
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,
}

pub fn process_slash(ctx: Context<Slash>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let caller_key = ctx.accounts.caller.key();
    let recipient_key = ctx.accounts.penalty_recipient.key();
    let alarm = &ctx.accounts.alarm;
    let clock = Clock::get()?;

    // CRITICAL: Can only slash AFTER deadline
    require!(
        clock.unix_timestamp >= alarm.deadline,
        SolarmaError::DeadlineNotPassed
    );

    // Validate penalty recipient based on route
    let route = PenaltyRoute::try_from(alarm.penalty_route)
        .map_err(|_| SolarmaError::InvalidPenaltyRoute)?;

    match route {
        PenaltyRoute::Burn => {
            require!(
                ctx.accounts.penalty_recipient.key() == BURN_SINK,
                SolarmaError::InvalidPenaltyRecipient
            );
        }
        PenaltyRoute::Donate => {
            let expected = alarm
                .penalty_destination
                .ok_or(SolarmaError::PenaltyDestinationNotSet)?;
            require!(
                ctx.accounts.penalty_recipient.key() == expected,
                SolarmaError::InvalidPenaltyRecipient
            );
        }
        PenaltyRoute::Buddy => {
            let expected = alarm
                .penalty_destination
                .ok_or(SolarmaError::PenaltyDestinationNotSet)?;
            // Whoever calls, the deposit can only go to the buddy.
            require!(
                ctx.accounts.penalty_recipient.key() == expected,
                SolarmaError::InvalidPenaltyRecipient
            );
        }
    }

    // The `close = penalty_recipient` constraint automatically transfers
    // all lamports (rent + remaining deposit) to penalty_recipient

    let slashed = alarm.remaining_amount;

    emit!(crate::events::AlarmSlashed {
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        penalty_recipient: recipient_key,
        slashed_amount: slashed,
        caller: caller_key,
    });

    msg!("Slashed {} lamports to {:?}", slashed, route);

    // Terminal: vault is closed to the penalty recipient, alarm to the owner.
    msg!("Alarm slashed by {}", caller_key);
    Ok(())
}
