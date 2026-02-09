//! Emergency refund instruction - owner can cancel alarm and get deposit back

use crate::constants::{BURN_SINK, EMERGENCY_REFUND_PENALTY_PERCENT};
use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, Vault};
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct EmergencyRefund<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
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

    /// Sink account receives emergency refund penalty
    /// CHECK: Validated against BURN_SINK constant
    #[account(
        mut,
        constraint = sink.key() == BURN_SINK @ SolarmaError::InvalidSinkAddress
    )]
    pub sink: UncheckedAccount<'info>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_emergency_refund(ctx: Context<EmergencyRefund>) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    // CRITICAL: Can only refund BEFORE alarm time
    // This is the escape hatch if something goes wrong
    require!(
        clock.unix_timestamp < alarm.alarm_time,
        SolarmaError::TooLateForRefund
    );

    // Calculate penalty (e.g., 5% fee for early cancellation)
    let penalty = alarm
        .remaining_amount
        .checked_mul(EMERGENCY_REFUND_PENALTY_PERCENT)
        .ok_or(SolarmaError::Overflow)?
        .checked_div(100)
        .ok_or(SolarmaError::Overflow)?;

    // C1: Rent-exempt guard — cap penalty at available balance above rent minimum.
    // The `close = owner` constraint processes AFTER this handler, so we must
    // ensure the vault stays above rent-exempt during penalty deduction.
    let final_penalty = if penalty > 0 {
        let rent = Rent::get()?;
        let vault_info = ctx.accounts.vault.to_account_info();
        let min_balance = rent.minimum_balance(vault_info.data_len());
        let current_lamports = vault_info.lamports();
        let available = current_lamports.saturating_sub(min_balance);
        let capped = penalty.min(available);

        if capped > 0 {
            **ctx
                .accounts
                .vault
                .to_account_info()
                .try_borrow_mut_lamports()? -= capped;
            **ctx
                .accounts
                .sink
                .to_account_info()
                .try_borrow_mut_lamports()? += capped;
        }
        capped
    } else {
        0
    };

    // The `close = owner` constraint returns remaining vault lamports to owner.
    // For accurate event emission, calculate what the user actually receives:
    // vault lamports after penalty deduction (includes rent-exempt balance).
    let actual_returned = ctx.accounts.vault.to_account_info().lamports();

    emit!(crate::events::EmergencyRefundExecuted {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        penalty_amount: final_penalty,
        returned_amount: actual_returned,
    });

    // Mark as claimed (terminal state)
    alarm.status = AlarmStatus::Claimed;
    alarm.remaining_amount = 0;

    msg!("Alarm cancelled by owner {}", owner_key);
    Ok(())
}
