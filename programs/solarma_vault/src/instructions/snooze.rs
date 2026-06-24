//! Snooze instruction - reduce deposit for extra time

use crate::constants::{BURN_SINK, DEFAULT_SNOOZE_EXTENSION_SECONDS, MAX_SNOOZE_COUNT};
use crate::error::SolarmaError;
use crate::helpers;
use crate::state::{Alarm, AlarmStatus, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct Snooze<'info> {
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
    pub vault: Account<'info, Vault>,

    /// Sink account receives snooze penalties
    /// CHECK: This is validated against the BURN_SINK constant
    #[account(
        mut,
        constraint = sink.key() == BURN_SINK @ SolarmaError::InvalidSinkAddress
    )]
    pub sink: UncheckedAccount<'info>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_snooze(ctx: Context<Snooze>, expected_snooze_count: u8) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    // CRITICAL: Cannot snooze BEFORE alarm time
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );

    // Check deadline not passed
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );

    // Check snooze limit
    require!(
        alarm.snooze_count < MAX_SNOOZE_COUNT,
        SolarmaError::MaxSnoozesReached
    );

    // H1: Idempotent snooze — prevent duplicate snooze from retry.
    // Client passes current expected snooze_count. If it doesn't match,
    // the transaction was already processed (retry with new blockhash).
    require!(
        alarm.snooze_count == expected_snooze_count,
        SolarmaError::InvalidAlarmState
    );

    // Calculate snooze cost (exponential: 10% * 2^snooze_count, capped at remaining)
    let cost = helpers::snooze_cost(alarm.remaining_amount, alarm.snooze_count)
        .ok_or(SolarmaError::Overflow)?;
    require!(cost > 0, SolarmaError::InsufficientDeposit);

    // C1: Rent-exempt guard — never drain vault below rent-exempt minimum.
    // If we did, the Solana runtime would garbage-collect the account,
    // making both claim and slash impossible (irrecoverable fund loss).
    let rent = Rent::get()?;
    let vault_info = ctx.accounts.vault.to_account_info();
    let min_balance = rent.minimum_balance(vault_info.data_len());
    let final_cost = helpers::cap_at_rent_exempt(cost, vault_info.lamports(), min_balance);
    require!(final_cost > 0, SolarmaError::InsufficientDeposit);

    // Transfer penalty from vault to sink
    **ctx
        .accounts
        .vault
        .to_account_info()
        .try_borrow_mut_lamports()? -= final_cost;
    **ctx.accounts.sink.try_borrow_mut_lamports()? += final_cost;

    // Update alarm state
    alarm.remaining_amount = alarm
        .remaining_amount
        .checked_sub(final_cost)
        .ok_or(SolarmaError::Overflow)?;

    alarm.snooze_count = alarm
        .snooze_count
        .checked_add(1)
        .ok_or(SolarmaError::Overflow)?;

    let (new_alarm_time, new_deadline) = helpers::snooze_time_extension(
        alarm.alarm_time,
        alarm.deadline,
        DEFAULT_SNOOZE_EXTENSION_SECONDS,
    )
    .ok_or(SolarmaError::Overflow)?;
    alarm.alarm_time = new_alarm_time;
    alarm.deadline = new_deadline;

    emit!(crate::events::AlarmSnoozed {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        snooze_count: alarm.snooze_count,
        cost: final_cost,
        remaining: alarm.remaining_amount,
        new_alarm_time: alarm.alarm_time,
        new_deadline: alarm.deadline,
    });

    msg!(
        "Snooze #{}: cost={}, remaining={}",
        alarm.snooze_count,
        final_cost,
        alarm.remaining_amount
    );
    Ok(())
}
