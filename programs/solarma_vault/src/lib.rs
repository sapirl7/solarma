//! Solarma Vault Program
//! 
//! An onchain commitment vault for the Solarma alarm app.
//! Users deposit SOL/SPL tokens when setting alarms. They claim back
//! after completing wake proof, or the deposit is slashed after deadline.

use anchor_lang::prelude::*;

declare_id!("So1armaVau1t1111111111111111111111111111111");

pub mod constants;
pub mod error;
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
        instructions::initialize::handler(ctx)
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
        instructions::create_alarm::handler(
            ctx, 
            alarm_id,
            alarm_time, 
            deadline, 
            deposit_amount,
            penalty_route, 
            penalty_destination,
        )
    }

    /// Claim the remaining deposit (before deadline)
    pub fn claim(ctx: Context<Claim>) -> Result<()> {
        instructions::claim::handler(ctx)
    }

    /// Snooze the alarm (reduces deposit)
    pub fn snooze(ctx: Context<Snooze>) -> Result<()> {
        instructions::snooze::handler(ctx)
    }

    /// Slash the deposit after deadline (permissionless)
    pub fn slash(ctx: Context<Slash>) -> Result<()> {
        instructions::slash::handler(ctx)
    }

    /// Emergency refund - owner can cancel before alarm time
    pub fn emergency_refund(ctx: Context<EmergencyRefund>) -> Result<()> {
        instructions::emergency_refund::handler(ctx)
    }
}
