//! Initialize user profile

use crate::state::UserProfile;
use anchor_lang::prelude::*;

#[derive(Accounts)]
pub struct Initialize<'info> {
    #[account(
        init,
        payer = owner,
        space = UserProfile::SIZE,
        seeds = [b"user-profile", owner.key().as_ref()],
        bump
    )]
    pub user_profile: Account<'info, UserProfile>,

    #[account(mut)]
    pub owner: Signer<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_initialize(ctx: Context<Initialize>) -> Result<()> {
    let user_profile = &mut ctx.accounts.user_profile;
    user_profile.owner = ctx.accounts.owner.key();
    user_profile.tag_hash = None;
    user_profile.bump = ctx.bumps.user_profile;

    emit!(crate::events::ProfileInitialized {
        owner: ctx.accounts.owner.key(),
    });

    msg!("User profile initialized for {}", ctx.accounts.owner.key());
    Ok(())
}
