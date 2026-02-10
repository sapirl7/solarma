# Security Policy

## Supported Versions

| Version | Status | Notes |
|---------|--------|-------|
| 0.2.x | ✅ Active | Current development, Devnet only |
| < 0.2.0 | ❌ Unsupported | No patches |

> ⚠️ **Solarma is currently on Devnet.** No real funds are at risk. However, we treat all vulnerabilities seriously to prepare for Mainnet.

---

## Reporting a Vulnerability

### ⛔ Do NOT

- Open a public GitHub issue
- Post on social media or Discord
- Exploit the vulnerability beyond proof-of-concept
- Share with third parties before resolution

### ✅ Do

**Email:** Send a detailed report to the maintainer via [GitHub Security Advisories](https://github.com/sapirl7/solarma/security/advisories/new).

If you prefer email, reach out to the repository owner directly (see profile).

### What to Include

| Field | Details |
|-------|---------|
| **Summary** | One-line description of the vulnerability |
| **Severity** | Critical / High / Medium / Low |
| **Affected component** | Smart contract, Android app, CI, or infra |
| **Steps to reproduce** | Minimal, step-by-step instructions |
| **Impact** | What an attacker could achieve |
| **Suggested fix** | Optional, but appreciated |
| **Solana Explorer links** | If on-chain, include transaction signatures |

### Response Timeline

| Stage | Target |
|-------|--------|
| Acknowledgment | 48 hours |
| Initial assessment | 5 business days |
| Fix development | Depends on severity |
| Disclosure | Coordinated with reporter |

---

## Security Architecture

### Smart Contract Invariants

The Solarma Vault program enforces these critical invariants:

| Invariant | How | File |
|-----------|-----|------|
| **No vault drain below rent-exempt** | `cap_at_rent_exempt()` guard before every deduction | `helpers.rs` |
| **No arithmetic overflow** | All math uses `checked_*` operations | All instruction handlers |
| **Idempotent snooze** | `expected_snooze_count` parameter prevents replay | `snooze.rs` |
| **Terminal state finality** | Claimed/Slashed alarms cannot transition further | `state.rs` |
| **Permissionless slash** | Anyone can trigger after deadline — no keeper dependency | `slash.rs` |
| **Penalty routing validation** | Recipient address verified against route (Burn→sink, Buddy→destination) | `slash.rs` |
| **Time-locked refund** | Emergency refund only before alarm time | `emergency_refund.rs` |
| **Minimum deposit** | Enforced at 0.001 SOL to prevent dust attacks | `create_alarm.rs` |

### Android App Security

| Control | Implementation |
|---------|---------------|
| **Non-custodial** | Keys never leave the wallet; MWA signs transactions |
| **No backend** | Serverless architecture — no API keys, no user data stored |
| **Local-only data** | Alarm cache in Room DB, no cloud sync |
| **Hardened build** | ProGuard/R8 minification in release builds |

---

## Audit Status

| Audit | Status | Notes |
|-------|--------|-------|
| Internal code review | ✅ Complete | Overflow, rent-exempt, state machine |
| Automated (clippy + deny warnings) | ✅ CI enforced | Every push |
| Formal security audit | 🔲 Planned | Required before Mainnet |

---

## Known Limitations

1. **Devnet only** — Program is deployed to Devnet (`F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP`). Not audited for Mainnet.
2. **Clock dependency** — Alarm timing relies on `Clock::get()` which can have slight validator skew (typically < 1 second).
3. **No upgrade authority** — Program is immutable once deployed. Fixes require redeployment to a new program ID.

---

## Bug Bounty

Currently, there is no formal bug bounty program. However, we deeply value security research and will credit all valid reports in our release notes. A formal program will be established before Mainnet launch.

---

<p align="center">
  <sub>Security is a shared responsibility. Thank you for helping keep Solarma safe 🔒</sub>
</p>
