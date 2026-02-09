# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.2.x   | ✅ Current |
| < 0.2.0 | ❌ |

## Reporting a Vulnerability

If you discover a security vulnerability in Solarma, **please report it responsibly**.

### Do NOT

- Open a public GitHub issue
- Post about it on social media
- Exploit the vulnerability

### Do

- Email: **security@solarma.app** (or DM on X: [@solarma_app](https://x.com/solarma_app))
- Include:
  - Description of the vulnerability
  - Steps to reproduce
  - Potential impact assessment
  - Suggested fix (if any)

### Response Timeline

| Action | Timeline |
|--------|----------|
| Acknowledgment | Within 48 hours |
| Initial assessment | Within 1 week |
| Fix deployed | Within 2 weeks (critical) |

## Scope

### In Scope

- Smart contract (`programs/solarma_vault/src/`)
  - Deposit/claim/slash logic
  - PDA derivation and seed collisions
  - Arithmetic overflow or underflow
  - Rent-exempt guard bypasses
  - State transition violations
- Android app (`apps/android/`)
  - Transaction signing vulnerabilities
  - Private key handling
  - MWA integration issues

### Out of Scope

- Solana network-level issues
- Devnet-only test infrastructure
- UI/UX improvements
- Feature requests

## Audit Status

> ⚠️ **This smart contract has NOT been independently audited.**
>
> While the code follows Solana/Anchor best practices (checked arithmetic,
> rent-exempt guards, proper PDA validation), it has not undergone a
> formal third-party security audit. Use at your own risk on devnet.

## Security Measures in Code

- ✅ All arithmetic uses `checked_*` operations (no panics)
- ✅ Rent-exempt guards prevent account garbage collection (C1)
- ✅ Idempotent snooze with `expected_snooze_count` (H1)
- ✅ On-chain wake proof narrows slash window (H3)
- ✅ `close = recipient` constraint for safe lamport transfers
- ✅ PDA seeds include `alarm_id` to prevent collisions
- ✅ Penalty recipient validated against stored destination
- ✅ Gitleaks CI prevents secret leakage
