//! Solarma Vault Program
//!
//! An onchain commitment vault for the Solarma alarm app.
//! Users deposit SOL when setting alarms. They claim back
//! after completing wake proof, or the deposit is slashed after deadline.

// create_alarm takes 7 instruction arguments; the wrappers #[program] generates for it
// can't carry a per-function allow.
#![allow(clippy::too_many_arguments)]

use anchor_lang::prelude::*;

declare_id!("F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP");

#[cfg(not(feature = "no-entrypoint"))]
solana_security_txt::security_txt! {
    name: "Solarma Vault",
    project_url: "https://github.com/sapirl7/solarma",
    contacts: "link:https://github.com/sapirl7/solarma/security/advisories/new",
    policy: "https://github.com/sapirl7/solarma/blob/main/SECURITY.md",
    source_code: "https://github.com/sapirl7/solarma"
}

pub mod constants;
pub mod error;
pub mod events;
pub mod helpers;
pub mod instructions;
pub mod state;

#[cfg(test)]
mod prop_tests;
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

    /// Create the global config (pause switch, deposit cap). Upgrade authority only.
    pub fn initialize_config(
        ctx: Context<InitializeConfig>,
        admin: Pubkey,
        max_deposit_lamports: u64,
    ) -> Result<()> {
        instructions::config::process_initialize_config(ctx, admin, max_deposit_lamports)
    }

    /// Pause/unpause alarm creation, change the deposit cap, or hand over admin. Admin only.
    pub fn update_config(
        ctx: Context<UpdateConfig>,
        paused: bool,
        max_deposit_lamports: u64,
        new_admin: Option<Pubkey>,
    ) -> Result<()> {
        instructions::config::process_update_config(ctx, paused, max_deposit_lamports, new_admin)
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
        ack_authority: Option<Pubkey>,
    ) -> Result<()> {
        instructions::create_alarm::process_create_alarm(
            ctx,
            alarm_id,
            alarm_time,
            deadline,
            deposit_amount,
            penalty_route,
            penalty_destination,
            ack_authority,
        )
    }

    /// Claim the remaining deposit (for acknowledged alarms, with grace)
    pub fn claim(ctx: Context<Claim>) -> Result<()> {
        instructions::claim::process_claim(ctx)
    }

    /// Snooze the alarm (reduces deposit).
    /// `expected_snooze_count` — current snooze count (idempotency guard).
    pub fn snooze(ctx: Context<Snooze>, expected_snooze_count: u8) -> Result<()> {
        instructions::snooze::process_snooze(ctx, expected_snooze_count)
    }

    /// Slash the deposit after deadline (Created only; buddy-only subwindow for Buddy route)
    pub fn slash(ctx: Context<Slash>) -> Result<()> {
        instructions::slash::process_slash(ctx)
    }

    /// Permissionless sweep after claim grace for acknowledged alarms.
    pub fn sweep_acknowledged(ctx: Context<SweepAcknowledged>) -> Result<()> {
        instructions::sweep_acknowledged::process_sweep_acknowledged(ctx)
    }

    /// Emergency refund - owner can cancel before alarm time
    pub fn emergency_refund(ctx: Context<EmergencyRefund>) -> Result<()> {
        instructions::emergency_refund::process_emergency_refund(ctx)
    }

    /// H3: Record wake proof completion on-chain
    pub fn ack_awake(ctx: Context<AckAwake>) -> Result<()> {
        instructions::ack_awake::process_ack_awake(ctx)
    }

    /// Acknowledge and return the deposit in one step; signable by the owner
    /// or the alarm's ack authority (device key).
    pub fn ack_and_claim(ctx: Context<AckAndClaim>) -> Result<()> {
        instructions::ack_and_claim::process_ack_and_claim(ctx)
    }
}
