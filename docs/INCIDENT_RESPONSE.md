# Incident Response

How to handle production issues for Solarma. Written for a solo maintainer — all
roles below are held by one person, but the discipline remains the same.

## Severity Levels

| Level | Definition | Response time |
|-------|-----------|---------------|
| **Sev0** | Funds at risk, on-chain exploit, loss-of-funds scenario | Drop everything |
| **Sev1** | User-impacting: transactions fail, false slashing, wrong penalty math | Same day |
| **Sev2** | Degraded UX, cosmetic bugs, isolated edge cases | Next session |

## First 30 Minutes (Sev0/Sev1)

1. **Open an incident issue** with a timestamped log of actions.
2. **Freeze risky flows** at the UX layer:
   - Push a hotfix disabling create/snooze/claim in the Android app, OR
   - If the app is sideloaded (no Play Store), communicate via GitHub Advisory.
3. **Preserve evidence**:
   - Transaction signatures → Solana Explorer links
   - App version + build hash (`git rev-parse HEAD`)
   - Steps to reproduce

## On-Chain Program Bug

1. **Classify**: correctness (bad state transition), economic (wrong penalty/rent),
   or authorization (who can call what).
2. **Mitigate**:
   - UI hard stop (disable affected instruction calls in app).
   - If the program has upgrade authority: deploy a fix.
   - If immutable: deploy to a new Program ID + migration plan.
3. **Communicate**: post a GitHub Security Advisory. Pin a README banner if
   user-facing.

## Mobile App Bug

Symptoms: wrong accounts in transaction, bad PDA seeds, wrong Program ID.

1. Disable the affected flow in the app.
2. Build + push a hotfix APK.
3. Add a snapshot test to prevent regression (see `TransactionSnapshotTest.kt`).

## Postmortem (within 72h)

Even as a solo dev, write a short postmortem:

- **Timeline**: when it started, when detected, when fixed.
- **Root cause**: what exactly went wrong.
- **Gap**: why existing tests/invariants didn't catch it.
- **Remediation**: what test, invariant, or process change prevents recurrence.

Store postmortems in `docs/postmortems/YYYY-MM-DD_title.md`.

## Communication

Use **one** canonical channel for user-facing updates:

- [GitHub Security Advisories](https://github.com/sapirl7/solarma/security/advisories)
  for security issues
- GitHub Releases / README banner for non-security incidents
