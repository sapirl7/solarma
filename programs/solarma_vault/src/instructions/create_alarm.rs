//! Create alarm instruction

use anchor_lang::prelude::*;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute};
use crate::error::SolarmaError;

#[derive(Accounts)]
pub struct CreateAlarm<'info> {
    #[account(
        init,
        payer = owner,
        space = Alarm::SIZE,
        seeds = [b"alarm", owner.key().as_ref(), &Clock::get()?.unix_timestamp.to_le_bytes()],
        bump
    )]
    pub alarm: Account<'info, Alarm>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
    
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<CreateAlarm>,
    alarm_time: i64,
    deadline: i64,
    penalty_route: u8,
) -> Result<()> {
    // Validate penalty route
    let _route = PenaltyRoute::try_from(penalty_route)
        .map_err(|_| SolarmaError::InvalidPenaltyRoute)?;
    
    let alarm = &mut ctx.accounts.alarm;
    alarm.owner = ctx.accounts.owner.key();
    alarm.alarm_time = alarm_time;
    alarm.deadline = deadline;
    alarm.deposit_mint = None;
    alarm.initial_amount = 0;
    alarm.remaining_amount = 0;
    alarm.penalty_route = penalty_route;
    alarm.penalty_destination = None;
    alarm.snooze_count = 0;
    alarm.status = AlarmStatus::Created;
    alarm.bump = ctx.bumps.alarm;
    
    msg!("Alarm created for {} at {}", ctx.accounts.owner.key(), alarm_time);
    Ok(())
}
