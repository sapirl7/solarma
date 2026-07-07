# ADR-0007: On-chain Wake-Proof Enforcement (Commitment / Reveal)

**Date**: 2026-07-01
**Status**: Proposed (on-chain implemented behind a coordinated client change; not deployed)

### Context

The product premise is "stake SOL on your alarm — wake up or lose it." Security
audit finding **H1** showed the premise was **not enforced on-chain**:
`ack_awake` transitioned `Created → Acknowledged` on the owner's signature plus a
time window alone, verifying no proof. `UserProfile.tag_hash` was never read. An
owner could therefore acknowledge and claim without ever completing a wake
proof, so the deposit was never actually at risk.

A blockchain cannot observe the physical world (the oracle problem): it cannot
know whether the user walked, tapped an NFC tag, or scanned a QR code. The best
achievable on-chain guarantee is to bind the deposit to a **secret that can only
be obtained by performing the physical action**, or to a **trusted witness's
signature**.

### Decision

Adopt a **commitment / reveal** scheme:

- At `create_alarm`, the client stores `wake_commitment = blake3(preimage ‖
  owner ‖ alarm_id_le)` on the `Alarm` account. The secret `preimage` never
  goes on-chain.
- At `ack_awake`, the owner reveals `preimage`; the program recomputes the same
  blake3 digest and requires it to equal the stored `wake_commitment`.
- Binding the digest to `owner` + `alarm_id` lets the same physical secret (one
  NFC tag / QR code) back many alarms without cross-alarm replay.
- An all-zero commitment (`NO_WAKE_PROOF`) means "no proof required" (None mode /
  zero-stake). blake3 can never output all-zero, so a real commitment can never
  collide with the sentinel.
- A **staked** alarm (`deposit_amount > 0`) MUST carry a non-zero commitment
  (`WakeCommitmentRequired`), so a deposit can only be reclaimed by revealing the
  secret.

**Hash choice:** blake3. It is already a program dependency (`=1.5.5`), is
BPF-compatible (Solana itself uses it), and avoids the version-fragile
`solana_program::hash` module path. Any collision-resistant hash would do.

### What this does and does NOT guarantee

- It is a **self-commitment device**, not an adversarial proof of wakefulness. It
  proves the caller **possesses the secret**, not that they physically got up.
  If the user keeps the NFC tag on the nightstand or photographs the QR, they can
  reveal without waking — the security is only as strong as the user placing the
  secret out of reach. This is the correct model: the counterparty is the user's
  own future self, not a malicious third party who profits from their failure.
- **NFC / QR** modes fit this scheme directly (the tag/code carries the secret).
- **Steps** mode has no cryptographic secret and cannot be made trustless
  on-chain; a staked Steps alarm needs either an oracle/attestation or must be
  offered only as non-staking.

### Alternatives considered

1. **Ed25519 attestation** (store `attestor: Pubkey`; require an Ed25519Program
   instruction in the same tx signed by the attestor over `{alarm_id, owner,
   slot}`, verified via the Instructions sysvar). Stronger — a third party
   (server/buddy) witnesses the event — but needs infrastructure and shifts
   trust to the attestor. Keep as a future option for Steps / buddy-verified
   alarms.
2. **Keep proof client-side** (status quo). Rejected: leaves H1 open; the deposit
   is never at risk.

### Consequences

- **Instruction interface changes** (breaking):
  - `create_alarm(..., wake_commitment: [u8; 32])` — appended arg.
  - `ack_awake(wake_preimage: [u8; 32])` — new arg (accounts unchanged).
  - `Alarm` gains `wake_commitment: [u8; 32]` (carved from the 64-byte padding;
    account size unchanged).
- **Must ship in lockstep with the client.** If the enforcement is deployed
  before the Android client sends a valid preimage, **every `ack_awake` fails →
  no one can claim → all staked deposits get slashed.** Coordinate the redeploy
  with the program-ID rotation and a matching app release.
- The TS integration suite and Android `SolarmaInstructionBuilder` /
  `WakeProofEngine` must be updated (see the PR checklist).

### Client integration spec

- **preimage** (`[u8; 32]`): a high-entropy secret derived from the registered
  NFC tag / QR content (e.g. `blake3(tag_bytes)`), stored locally (as the NFC
  hash already is). Must be unguessable.
- **create_alarm**: compute `wake_commitment = blake3(preimage ‖ owner(32) ‖
  alarm_id_le(8))`, serialize it as 32 raw bytes appended after
  `penalty_destination` in the instruction data.
- **ack_awake**: serialize `preimage` as the 32-byte instruction argument.
- **None / zero-stake** alarms send `wake_commitment = [0u8; 32]` and any
  preimage.
