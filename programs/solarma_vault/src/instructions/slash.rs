//! Slash instruction - transfer deposit after deadline (permissionless)

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, Vault};
use crate::error::SolarmaError;
use crate::constants::BURN_SINK;

#[derive(Accounts)]
pub struct Slash<'info> {
    #[account(
        mut,
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

pub fn handler(ctx: Context<Slash>) -> Result<()> {
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
        },
        PenaltyRoute::Donate | PenaltyRoute::Buddy => {
            if let Some(expected) = alarm.penalty_destination {
                require!(
                    ctx.accounts.penalty_recipient.key() == expected,
                    SolarmaError::InvalidPenaltyRecipient
                );
            } else {
                return Err(SolarmaError::PenaltyDestinationNotSet.into());
            }
        },
    }
    
    // The `close = penalty_recipient` constraint automatically transfers
    // all lamports (rent + remaining deposit) to penalty_recipient
    
    msg!("Slashed {} lamports to {:?}", alarm.remaining_amount, route);
    
    // Mark as slashed (terminal state)
    alarm.status = AlarmStatus::Slashed;
    alarm.remaining_amount = 0;
    
    msg!("Alarm slashed by {}", ctx.accounts.caller.key());
    Ok(())
}
