# ADR-0008: Attestation Permit (Server-Signed ACK) + On-Chain Ed25519 Verification

**Status:** Accepted  
**Date:** 2026-02-10  
**Author:** sapirl7

## Context

Wake proof is currently enforced client-side (steps/NFC/QR). Device sensors and app logic
are outside of the program's trust boundary, so a motivated user can attempt to cheat by:

- Modifying the app (bypass proof gating).
- Spoofing sensor APIs.
- Replaying previously captured client-side states.

We want an **optional** mode that raises the bar against casual cheating, without changing
the custody model (wallet still signs, program still controls funds), and without changing
the `Alarm` account layout.

## Decision

Introduce an optional instruction `ack_awake_attested` that requires a server-signed permit:

1. The transaction MUST include an Ed25519 verification instruction immediately before
   `ack_awake_attested`.
2. The program reads the Instructions sysvar and validates that the verified message equals
   a canonical, domain-separated format that binds:
   - cluster + program_id (cross-deploy replay guard),
   - (alarm_pda, owner),
   - nonce (anti-replay),
   - expiry,
   - proof_type and proof_hash.
3. Anti-replay is enforced by creating a separate PDA account (`PermitNonce`) at:
   - `seeds = ["permit", alarm_pda, nonce_le_u64]`
   - initialization fails if the nonce was already used.

The canonical message is:

```
solarma|ack|<cluster>|<program_id>|<alarm_pda>|<owner>|<nonce>|<exp_ts>|<proof_type>|<proof_hash_hex>
```

See `docs/ATTESTATION_SERVER_SPEC.md`.

## Rationale

- **Optional mode:** default `ack_awake` remains available; users can opt-in.
- **No Alarm layout change:** nonce replay tracking uses a separate PDA.
- **On-chain verification:** the program verifies an Ed25519 verify instruction was present
  in the same transaction and that it matched the expected signer and message bytes.

## Consequences

- Adds an operational dependency if enabled: key management for the attestation signer and
  server availability.
- Introduces bounded on-chain state growth: one small `PermitNonce` account per used nonce.
  (Acceptable for now; can be extended with cleanup later.)
- Does NOT solve fully adversarial scenarios (rooted devices, colluding server, etc).

## Alternatives Considered

- Store `nonce` in `Alarm`: rejected (account layout change).
- Require server signatures for all actions: rejected (centralization, liveness risk).
- Pure on-chain proof verification: rejected for now (hardware/sensor attestations are not
  generally verifiable on-chain without additional infrastructure).

