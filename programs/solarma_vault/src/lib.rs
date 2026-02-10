//! Solarma Vault Program
//!
//! An onchain commitment vault for the Solarma alarm app.
//! Users deposit SOL when setting alarms. They claim back
//! after completing wake proof, or the deposit is slashed after deadline.

use anchor_lang::prelude::*;

declare_id!("F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP");

pub mod constants;
pub mod error;
pub mod events;
pub mod helpers;
pub mod instructions;
pub mod state;

#[cfg(test)]
mod tests;

use instructions::*;

#[program]
pub mod solarma_vault {
    use super::*;

    /// Initialize a user profile with optional tag registration
    pub fn initialize(ctx: Context<Initialize>) -> Result<()> {
        instructions::initialize::process_initialize(ctx)
    }

    /// Create a new alarm with optional deposit
    pub fn create_alarm(
        ctx: Context<CreateAlarm>,
        alarm_id: u64,
        alarm_time: i64,
        deadline: i64,
        deposit_amount: u64,
        penalty_route: u8,
        penalty_destination: Option<Pubkey>,
    ) -> Result<()> {
        instructions::create_alarm::process_create_alarm(
            ctx,
            alarm_id,
            alarm_time,
            deadline,
            deposit_amount,
            penalty_route,
            penalty_destination,
        )
    }

    /// Claim the remaining deposit (requires ACK; allowed until `deadline + CLAIM_GRACE_SECONDS`)
    pub fn claim(ctx: Context<Claim>) -> Result<()> {
        instructions::claim::process_claim(ctx)
    }

    /// Snooze the alarm (reduces deposit).
    /// `expected_snooze_count` — current snooze count (idempotency guard).
    pub fn snooze(ctx: Context<Snooze>, expected_snooze_count: u8) -> Result<()> {
        instructions::snooze::process_snooze(ctx, expected_snooze_count)
    }

    /// Slash the deposit after deadline (Buddy route has an initial buddy-only window)
    pub fn slash(ctx: Context<Slash>) -> Result<()> {
        instructions::slash::process_slash(ctx)
    }

    /// Emergency refund - owner can cancel before alarm time
    pub fn emergency_refund(ctx: Context<EmergencyRefund>) -> Result<()> {
        instructions::emergency_refund::process_emergency_refund(ctx)
    }

    /// H3: Record wake proof completion on-chain
    pub fn ack_awake(ctx: Context<AckAwake>) -> Result<()> {
        instructions::ack_awake::process_ack_awake(ctx)
    }

    /// Permissionlessly return funds to owner if ACKed but never claimed within grace.
    pub fn sweep_acknowledged(ctx: Context<SweepAcknowledged>) -> Result<()> {
        instructions::sweep_acknowledged::process_sweep_acknowledged(ctx)
    }
}
