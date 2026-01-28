//! Create alarm instruction - with deposit support

use anchor_lang::prelude::*;
use anchor_lang::system_program;
use crate::state::{Alarm, AlarmStatus, PenaltyRoute, Vault};
use crate::error::SolarmaError;
use crate::constants::MIN_DEPOSIT_LAMPORTS;

#[derive(Accounts)]
#[instruction(alarm_id: u64, alarm_time: i64, deadline: i64, deposit_amount: u64)]
pub struct CreateAlarm<'info> {
    #[account(
        init,
        payer = owner,
        space = Alarm::SIZE,
        // Seeds include alarm_id to avoid collisions when same user
        // creates multiple alarms at similar times
        seeds = [b"alarm", owner.key().as_ref(), &alarm_id.to_le_bytes()],
        bump
    )]
    pub alarm: Account<'info, Alarm>,
    
    /// Vault PDA that holds the deposit - INITIALIZED here
    #[account(
        init,
        payer = owner,
        space = Vault::SIZE,
        seeds = [b"vault", alarm.key().as_ref()],
        bump
    )]
    pub vault: Account<'info, Vault>,
    
    #[account(mut)]
    pub owner: Signer<'info>,
    
    pub system_program: Program<'info, System>,
}

pub fn handler(
    ctx: Context<CreateAlarm>,
    alarm_id: u64,
    alarm_time: i64,
    deadline: i64,
    deposit_amount: u64,
    penalty_route: u8,
    penalty_destination: Option<Pubkey>,
) -> Result<()> {
    // Validate penalty route
    let route = PenaltyRoute::try_from(penalty_route)
        .map_err(|_| SolarmaError::InvalidPenaltyRoute)?;
    
    // Validate times
    let clock = Clock::get()?;
    require!(
        alarm_time > clock.unix_timestamp,
        SolarmaError::AlarmTimeInPast
    );
    require!(
        deadline > alarm_time,
        SolarmaError::InvalidDeadline
    );
    
    // Validate deposit if provided
    if deposit_amount > 0 {
        require!(
            deposit_amount >= MIN_DEPOSIT_LAMPORTS,
            SolarmaError::DepositTooSmall
        );
        
        // Buddy route requires destination
        if route == PenaltyRoute::Buddy {
            require!(
                penalty_destination.is_some(),
                SolarmaError::BuddyAddressRequired
            );
        }
        
        // Transfer SOL to vault
        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.owner.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                },
            ),
            deposit_amount,
        )?;
    }
    
    // Initialize vault
    let vault = &mut ctx.accounts.vault;
    vault.alarm = ctx.accounts.alarm.key();
    vault.bump = ctx.bumps.vault;
    
    // Initialize alarm
    let alarm = &mut ctx.accounts.alarm;
    alarm.owner = ctx.accounts.owner.key();
    alarm.alarm_time = alarm_time;
    alarm.deadline = deadline;
    alarm.deposit_mint = None; // SOL deposit
    alarm.initial_amount = deposit_amount;
    alarm.remaining_amount = deposit_amount;
    alarm.penalty_route = penalty_route;
    alarm.penalty_destination = penalty_destination;
    alarm.snooze_count = 0;
    alarm.status = AlarmStatus::Created;
    alarm.bump = ctx.bumps.alarm;
    alarm.vault_bump = ctx.bumps.vault;
    
    msg!("Alarm {} created: time={}, deadline={}, deposit={}", 
         alarm_id, alarm_time, deadline, deposit_amount);
    Ok(())
}
