# Attestation Server Spec (Optional)

This document specifies the optional "attested ACK" flow used by the Android app
to obtain a server-signed permit, and by the Solana program to verify it on-chain.

Default (non-attested) flows remain supported.

## Overview

- Client completes wake proof locally (steps/NFC/QR).
- Client requests a signed permit from the attestation server.
- Client submits a transaction that includes:
  1. An Ed25519 signature verification instruction (`Ed25519SigVerify111111111111111111111111111`).
  2. The program instruction `ack_awake_attested(...)`.
- The program checks the verified message matches a canonical format and that the signer
  equals the configured on-chain `ATTESTATION_PUBKEY`.

## Canonical Permit Message

The server MUST sign the following UTF-8 message bytes exactly:

```
solarma|ack|<cluster>|<program_id>|<alarm_pda>|<owner>|<nonce>|<exp_ts>|<proof_type>|<proof_hash_hex>
```

Field rules:
- `cluster`: application-level string. Must match the program constant `ATTESTATION_CLUSTER`.
  - Current dev default: `devnet`
- `program_id`, `alarm_pda`, `owner`: base58-encoded Solana public keys.
- `nonce`: unsigned 64-bit integer, decimal string (no separators).
- `exp_ts`: unix timestamp (seconds), signed 64-bit integer, decimal string.
- `proof_type`: unsigned 8-bit integer, decimal string.
- `proof_hash_hex`: lowercase hex of 32 bytes (64 hex chars).

## Endpoint

### `POST /v1/permit/ack`

Request JSON:
```json
{
  "cluster": "devnet",
  "program_id": "<base58>",
  "alarm_pda": "<base58>",
  "owner": "<base58>",
  "nonce": "123",
  "exp_ts": 1700000060,
  "proof_type": 2,
  "proof_hash": "<64 lowercase hex chars>"
}
```

Response JSON (200):
```json
{
  "signature": "<base58 ed25519 signature (64 bytes)>"
}
```

Error responses:
- Non-200 responses SHOULD include a JSON body with a human-readable message.
- The client treats non-200 as retryable only when it looks transient (timeouts, etc).

## Signing Rules

- Signature algorithm: Ed25519 over the canonical message bytes.
- Signing key: must match the on-chain `ATTESTATION_PUBKEY` constant.
- `exp_ts` MUST be enforced by the server (reject expired, reject excessively long TTL).

## Security Notes

- This flow adds a server trust boundary: the server can approve ACKs.
- It does NOT prove "wakefulness" by itself; the server must implement proof verification
  policies if it is intended to raise the cheating bar.
- Replay is prevented on-chain using a nonce PDA; permits must not be reusable.

## Local Development

This repo documents the server API but does not ship a production server.

To test the flow end-to-end:

1. Run any small HTTP service that implements `POST /v1/permit/ack` and signs the canonical
   message with the Ed25519 private key corresponding to the on-chain `ATTESTATION_PUBKEY`.
2. Configure Android:
   - Set `SOLARMA_ATTESTATION_SERVER_URL` in `~/.gradle/gradle.properties`.
   - Enable "Attested Mode (beta)" in Settings.
3. Create a deposit alarm and complete wake proof; the app will queue `ACK_AWAKE_ATTESTED`
   and submit a tx containing Ed25519 verify + `ack_awake_attested`.
