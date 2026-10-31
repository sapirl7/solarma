//! Global configuration: pause switch and deposit cap, controlled by an admin
//! (intended to be a multisig).

use crate::constants::{CONFIG_SEED, MIN_DEPOSIT_LAMPORTS};
use crate::error::SolarmaError;
use crate::program::SolarmaVault;
use crate::state::Config;
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(
        init,
        payer = authority,
        space = Config::SIZE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, Config>,

    #[account(mut)]
    pub authority: Signer<'info>,

    /// The program itself, used to locate its ProgramData account.
    #[account(constraint = program.programdata_address()? == Some(program_data.key()) @ SolarmaError::Unauthorized)]
    pub program: Program<'info, SolarmaVault>,

    /// Only the upgrade authority may create the config, so nobody can front-run
    /// the deployment and appoint themselves admin.
    #[account(constraint = program_data.upgrade_authority_address == Some(authority.key()) @ SolarmaError::Unauthorized)]
    pub program_data: Account<'info, ProgramData>,

    pub system_program: Program<'info, System>,
}

pub fn process_initialize_config(
    ctx: Context<InitializeConfig>,
    admin: Pubkey,
    max_deposit_lamports: u64,
) -> Result<()> {
    require!(
        max_deposit_lamports >= MIN_DEPOSIT_LAMPORTS,
        SolarmaError::DepositTooSmall
    );

    let config = &mut ctx.accounts.config;
    config.admin = admin;
    config.paused = false;
    config.max_deposit_lamports = max_deposit_lamports;
    config.bump = ctx.bumps.config;

    emit!(crate::events::ConfigUpdated {
        admin,
        paused: false,
        max_deposit_lamports,
    });
    Ok(())
}

#[derive(Accounts)]
pub struct UpdateConfig<'info> {
    #[account(
        mut,
        seeds = [CONFIG_SEED],
        bump = config.bump,
        has_one = admin @ SolarmaError::Unauthorized
    )]
    pub config: Account<'info, Config>,

    pub admin: Signer<'info>,
}

pub fn process_update_config(
    ctx: Context<UpdateConfig>,
    paused: bool,
    max_deposit_lamports: u64,
    new_admin: Option<Pubkey>,
) -> Result<()> {
    require!(
        max_deposit_lamports >= MIN_DEPOSIT_LAMPORTS,
        SolarmaError::DepositTooSmall
    );

    let config = &mut ctx.accounts.config;
    config.paused = paused;
    config.max_deposit_lamports = max_deposit_lamports;
    if let Some(admin) = new_admin {
        config.admin = admin;
    }

    emit!(crate::events::ConfigUpdated {
        admin: config.admin,
        paused,
        max_deposit_lamports,
    });
    Ok(())
}
