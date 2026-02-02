# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

We take security seriously. If you discover a vulnerability:

### Private Disclosure (Preferred)

1. **DO NOT** open a public issue
2. Email: **security@solarma.app**
3. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

### Response Timeline

- **24 hours** — Initial acknowledgment
- **72 hours** — Assessment and severity classification
- **7 days** — Patch development (critical issues)
- **30 days** — Coordinated disclosure

## Scope

### In Scope
- Smart contract (`programs/solarma_vault/`)
- Android app security (wallet integration, key handling)
- Transaction signing logic
- PDA derivation and account validation

### Out of Scope
- UI/UX issues without security impact
- Third-party dependencies (report to maintainers)
- Devnet-only issues (unless they affect mainnet logic)

## Bug Bounty

We currently do not have a formal bug bounty program, but we appreciate responsible disclosure and will publicly credit researchers (with permission).

## Security Best Practices

### For Users
- Never share your seed phrase
- Verify transaction details before signing
- Only use official releases from GitHub

### For Contributors
- No secrets in code
- Review all transaction building logic
- Test edge cases thoroughly
