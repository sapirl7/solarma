//! AckAwakeAttested instruction - record wake proof completion using a signed permit.
//!
//! This is an optional hardening mode. The caller includes an Ed25519 verify instruction
//! in the same transaction, and the program checks that the verified message binds:
//! - cluster + program_id (cross-deploy replay guard)
//! - (alarm_pda, owner)
//! - nonce (anti-replay, stored in a separate PDA)
//! - expiry, proof_type, proof_hash

use crate::constants::{
    ATTESTATION_ACTION_ACK, ATTESTATION_CLUSTER, ATTESTATION_DOMAIN, ATTESTATION_PUBKEY,
};
use crate::error::SolarmaError;
use crate::state::{Alarm, AlarmStatus, PermitNonce};
use anchor_lang::prelude::*;
use solana_program::{ed25519_program, sysvar::instructions as sysvar_instructions};

#[derive(Accounts)]
#[instruction(nonce: u64)]
pub struct AckAwakeAttested<'info> {
    #[account(
        mut,
        has_one = owner,
        constraint = alarm.status == AlarmStatus::Created @ SolarmaError::InvalidAlarmState
    )]
    pub alarm: Account<'info, Alarm>,

    #[account(mut)]
    pub owner: Signer<'info>,

    /// Marks the permit nonce as used (anti-replay).
    #[account(
        init,
        payer = owner,
        space = PermitNonce::SIZE,
        seeds = [b"permit", alarm.key().as_ref(), nonce.to_le_bytes().as_ref()],
        bump
    )]
    pub permit_nonce: Account<'info, PermitNonce>,

    /// CHECK: Instructions sysvar (read-only) used to validate the Ed25519 verify instruction.
    #[account(address = sysvar_instructions::ID)]
    pub instructions: UncheckedAccount<'info>,

    pub system_program: Program<'info, System>,
}

pub fn process_ack_awake_attested(
    ctx: Context<AckAwakeAttested>,
    nonce: u64,
    exp_ts: i64,
    proof_type: u8,
    proof_hash: [u8; 32],
) -> Result<()> {
    let alarm_key = ctx.accounts.alarm.key();
    let owner_key = ctx.accounts.owner.key();
    let alarm = &mut ctx.accounts.alarm;
    let clock = Clock::get()?;

    // Standard ACK window checks (same as ack_awake).
    require!(
        clock.unix_timestamp >= alarm.alarm_time,
        SolarmaError::TooEarly
    );
    require!(
        clock.unix_timestamp < alarm.deadline,
        SolarmaError::DeadlinePassed
    );

    // Permit expiry.
    require!(clock.unix_timestamp <= exp_ts, SolarmaError::PermitExpired);

    // Verify the immediately preceding instruction is a matching Ed25519 verify.
    let ix_sysvar = ctx.accounts.instructions.to_account_info();
    let current_index = sysvar_instructions::load_current_index_checked(&ix_sysvar)
        .map_err(|_| error!(SolarmaError::MissingEd25519Verify))?;
    require!(current_index > 0, SolarmaError::MissingEd25519Verify);
    let prev_ix =
        sysvar_instructions::load_instruction_at_checked((current_index - 1) as usize, &ix_sysvar)
            .map_err(|_| error!(SolarmaError::MissingEd25519Verify))?;

    require!(
        prev_ix.program_id == ed25519_program::ID,
        SolarmaError::MissingEd25519Verify
    );

    let expected_message = build_ack_permit_message(
        ATTESTATION_CLUSTER,
        crate::ID,
        alarm_key,
        owner_key,
        nonce,
        exp_ts,
        proof_type,
        &proof_hash,
    );

    verify_ed25519_verify_ix(&prev_ix.data, &expected_message)?;

    // Mark nonce as used.
    ctx.accounts.permit_nonce.owner = owner_key;
    ctx.accounts.permit_nonce.expires_at = exp_ts;
    ctx.accounts.permit_nonce.bump = ctx.bumps.permit_nonce;

    // Transition to Acknowledged.
    alarm.status = AlarmStatus::Acknowledged;

    emit!(crate::events::WakeAcknowledged {
        owner: owner_key,
        alarm: alarm_key,
        alarm_id: alarm.alarm_id,
        timestamp: clock.unix_timestamp,
    });

    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn build_ack_permit_message(
    cluster: &str,
    program_id: Pubkey,
    alarm_pda: Pubkey,
    owner: Pubkey,
    nonce: u64,
    exp_ts: i64,
    proof_type: u8,
    proof_hash: &[u8; 32],
) -> Vec<u8> {
    let mut proof_hex = String::with_capacity(64);
    for b in proof_hash {
        let hi = b >> 4;
        let lo = b & 0x0f;
        proof_hex.push(nibble_to_hex(hi));
        proof_hex.push(nibble_to_hex(lo));
    }

    let mut s = String::with_capacity(256);
    s.push_str(ATTESTATION_DOMAIN);
    s.push('|');
    s.push_str(ATTESTATION_ACTION_ACK);
    s.push('|');
    s.push_str(cluster);
    s.push('|');
    s.push_str(&program_id.to_string());
    s.push('|');
    s.push_str(&alarm_pda.to_string());
    s.push('|');
    s.push_str(&owner.to_string());
    s.push('|');
    s.push_str(&nonce.to_string());
    s.push('|');
    s.push_str(&exp_ts.to_string());
    s.push('|');
    s.push_str(&proof_type.to_string());
    s.push('|');
    s.push_str(&proof_hex);

    s.into_bytes()
}

fn nibble_to_hex(n: u8) -> char {
    match n {
        0..=9 => (b'0' + n) as char,
        10..=15 => (b'a' + (n - 10)) as char,
        _ => '?',
    }
}

fn verify_ed25519_verify_ix(ix_data: &[u8], expected_message: &[u8]) -> Result<()> {
    // Ed25519Program data layout (1 signature):
    // u8 num_signatures
    // u8 padding
    // struct offsets (14 bytes)
    // public_key (32 bytes)
    // signature (64 bytes)
    // message (message_data_size bytes)
    require!(ix_data.len() >= 16, SolarmaError::InvalidEd25519Verify);
    require!(ix_data[0] == 1, SolarmaError::InvalidEd25519Verify);
    require!(ix_data[1] == 0, SolarmaError::InvalidEd25519Verify);

    let signature_offset = read_u16_le(ix_data, 2) as usize;
    let signature_ix = read_u16_le(ix_data, 4);
    let pubkey_offset = read_u16_le(ix_data, 6) as usize;
    let pubkey_ix = read_u16_le(ix_data, 8);
    let msg_offset = read_u16_le(ix_data, 10) as usize;
    let msg_size = read_u16_le(ix_data, 12) as usize;
    let msg_ix = read_u16_le(ix_data, 14);

    // Require all data to be embedded in the same ed25519 instruction (u16::MAX).
    require!(signature_ix == u16::MAX, SolarmaError::InvalidEd25519Verify);
    require!(pubkey_ix == u16::MAX, SolarmaError::InvalidEd25519Verify);
    require!(msg_ix == u16::MAX, SolarmaError::InvalidEd25519Verify);

    // Bounds checks.
    require!(
        pubkey_offset
            .checked_add(32)
            .map(|end| end <= ix_data.len())
            .unwrap_or(false),
        SolarmaError::InvalidEd25519Verify
    );
    require!(
        signature_offset
            .checked_add(64)
            .map(|end| end <= ix_data.len())
            .unwrap_or(false),
        SolarmaError::InvalidEd25519Verify
    );
    require!(
        msg_offset
            .checked_add(msg_size)
            .map(|end| end <= ix_data.len())
            .unwrap_or(false),
        SolarmaError::InvalidEd25519Verify
    );

    // Public key must match configured attestation pubkey.
    let pubkey_bytes = &ix_data[pubkey_offset..pubkey_offset + 32];
    require!(
        pubkey_bytes == ATTESTATION_PUBKEY.as_ref(),
        SolarmaError::AttestationPubkeyMismatch
    );

    // Message must match canonical expected bytes.
    require!(
        msg_size == expected_message.len(),
        SolarmaError::InvalidPermitMessage
    );
    let msg_bytes = &ix_data[msg_offset..msg_offset + msg_size];
    require!(
        msg_bytes == expected_message,
        SolarmaError::InvalidPermitMessage
    );

    Ok(())
}

fn read_u16_le(data: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([data[offset], data[offset + 1]])
}
