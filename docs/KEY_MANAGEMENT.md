# Key Management

How Solarma manages sensitive keys. Solo-maintainer context — all keys are held
by one developer, which makes discipline even more critical.

## Key Inventory

| Key | Purpose | Storage | Committed to git? |
|-----|---------|---------|-------------------|
| Solana upgrade authority | Deploy/upgrade on-chain program | Local machine, encrypted | **Never** |
| Solana deployer keypair | Same as above (solo dev) | Local machine | **Never** |
| Android release keystore (`.jks`) | Sign APK/AAB for distribution | Local machine, encrypted backup | **Never** |
| `keystore.properties` | References keystore path + password | Local only | **Never** (`.gitignore`'d) |
| RPC API keys (Helius, etc.) | Access to Solana RPC | `~/.gradle/gradle.properties` | **Never** |

## What is in the repo (safe)

| File | Contents | Why it's safe |
|------|----------|---------------|
| `keystore.properties.example` | Template with placeholder values | No real credentials |

## Prohibited (hard rules)

- ❌ Committing `*-keypair.json`, `*.jks`, seed phrases, or `.env` with secrets
- ❌ Storing private keys in CI environment variables
- ❌ Pasting keys into issues, PRs, or chat
- ❌ Using the same keypair for devnet and mainnet

## Rotation

### When to rotate

- Suspected compromise (machine lost/stolen, malware, accidental commit)
- Moving from devnet to mainnet
- After a security incident

### Solana upgrade authority

```bash
# 1. Generate new keypair (hardware wallet preferred)
solana-keygen new -o new-authority.json

# 2. Transfer authority
solana program set-upgrade-authority <PROGRAM_ID> \
  --new-upgrade-authority <NEW_PUBKEY>

# 3. Record: date, old pubkey, new pubkey, reason
```

See `docs/UPGRADE_POLICY.md` for full procedure.

### Android keystore

- If using Google Play App Signing: Google manages the upload key rotation.
- If self-managed: generate a new keystore, re-sign, update backup.

## Compromise Response

1. **Stop** all deployments immediately.
2. **Assess**: can the attacker upgrade the program? (Check `solana program show`.)
3. **Rotate** the compromised key.
4. **If program was altered**: deploy an emergency fix or freeze via UI.
5. **Document** in a postmortem (see `docs/INCIDENT_RESPONSE.md`).

## Backup

- Solana seed phrase: written on paper, stored in a physically separate location.
- Android keystore: encrypted copy on a separate drive.
- No cloud backups of raw key material.
