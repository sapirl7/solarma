# Threat Model

## Scope
- Android app (alarm creation, wake proof, wallet connection)
- Solana program (vault lifecycle: create/claim/snooze/slash)
- RPC providers and network interactions

## Assets
- User funds in vaults
- Alarm schedules and wake-proof configuration
- Wallet public keys and transaction metadata
- Program state and PDA integrity

## Trust Boundaries
- Mobile Wallet Adapter (Mwa) boundary: signing happens in wallet
- RPC provider: untrusted transport for requests/responses
- Onchain program: authoritative state
- Device sensors: untrusted inputs (NFC/QR/steps)
- Optional attestation server: trusted signer when "Attested Mode" is enabled

## Threats And Mitigations
- **Unauthorized fund movement**
  - Mitigation: program enforces PDA ownership, status transitions, and penalty routes
- **Replay or stale transaction use**
  - Mitigation: PDA seeds include owner + alarm_id, deadline checks in program
- **Malicious RPC responses**
  - Mitigation: verify onchain state after writes; use multiple RPC endpoints
- **Wake-proof bypass**
  - Mitigation: local validation and sensor gating; onchain deadline still enforces penalty
  - Optional hardening: `ack_awake_attested` requires a server-signed permit verified on-chain
- **Key exposure on device**
  - Mitigation: non-custodial signing via wallet; no private keys stored in app
- **Attestation server compromise**
  - Mitigation: strict key management and rotation; short permit expiries; treat server as an explicit trust boundary
- **Denial of service (rate-limited RPC)**
  - Mitigation: fallback endpoints and backoff (see README and Technical Spec)
- **Program ID mismatch**
  - Mitigation: Anchor.toml is source of truth; release checklist verifies program ID

## Residual Risks
- Sensor spoofing or device-specific behavior
- RPC outages or censorship
- User error in selecting penalty routes or amounts
- If enabled: attestation signer compromise can approve invalid ACKs

## References
- `docs/TECHNICAL_SPEC.md`
- `docs/QA_CHECKLIST.md`
- `docs/RELEASE_CHECKLIST.md`
- `docs/ATTESTATION_SERVER_SPEC.md`
