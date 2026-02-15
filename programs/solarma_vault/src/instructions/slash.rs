//! Slash instruction - transfer deposit after deadline (permissionless)

use crate::constants::{BUDDY_ONLY_SECONDS, BURN_SINK};
use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct Slash<'info> {
    #[account(
        mut,
        // Slash is only possible while alarm is still unresolved (Created).
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
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
}

pub fn process_slash(ctx: Context<Slash>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let caller_key = ctx.accounts.caller.key();
    let recipient_key = ctx.accounts.penalty_recipient.key();
    let alarm = &mut ctx.accounts.alarm;
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
            require!(
                ctx.accounts.penalty_recipient.key() == expected,
                SolarmaError::InvalidPenaltyRecipient
            );

            // During the first buddy-only window, only buddy can slash.
            let buddy_only_end = alarm
                .deadline
                .checked_add(BUDDY_ONLY_SECONDS)
                .ok_or(SolarmaError::Overflow)?;
            if clock.unix_timestamp < buddy_only_end {
                require!(caller_key == expected, SolarmaError::BuddyOnlyWindow);
            }
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

    // Mark as slashed (terminal state)
    alarm.status = AlarmStatus::Slashed;
    alarm.remaining_amount = 0;

    msg!("Alarm slashed by {}", caller_key);
    Ok(())
}
