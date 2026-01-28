# Security Policy

## Reporting Vulnerabilities

Please report security vulnerabilities privately via email to: security@solarma.app

**Do not** open public issues for security vulnerabilities.

## Scope

Security issues we care about:
- Smart contract vulnerabilities (Solana program)
- Wallet/key exposure
- Deposit theft or manipulation
- Alarm bypass that could cause deposit loss
- Privacy violations (sensor data leakage)

## Response Timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 1 week
- **Fix timeline**: Depends on severity (critical: ASAP)

## Disclosure Policy

- We practice coordinated disclosure
- Researchers will be credited (unless they prefer anonymity)
- We do not have a formal bug bounty program at this time

## Known Security Considerations

### Smart Contract
- Time-based security relies on Solana's clock
- Permissionless slash is by design (anyone can trigger after deadline)
- Deposits are held in program-controlled PDAs

### Mobile App
- Wallet keys never enter app (MWA isolation)
- Sensor data is processed locally only
- NFC tag hashes are stored, not raw identifiers
