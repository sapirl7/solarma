# Dependency Policy

This project uses [Dependabot](https://docs.github.com/en/code-security/dependabot) for automated dependency updates with the following strategy.

## Update Strategy

| Ecosystem | Schedule | Grouping |
|-----------|----------|----------|
| Gradle (Android) | Weekly (Monday) | AndroidX libs, DI (Hilt/Dagger) |
| npm (Anchor tests) | Weekly (Monday) | Test deps, Anchor SDK |
| Cargo (smart contract) | Monthly | Anchor framework |
| GitHub Actions | Monthly | — |

## Auto-merge Rules

Dependabot PRs are **auto-merged** when:
- CI passes (all jobs green)
- Update is **patch** or **minor**

**Major version** bumps always require manual review and approval.

## Protected Dependencies

These dependencies are excluded from major version auto-updates because breaking changes could affect on-chain program compatibility or wallet integration:

| Dependency | Ecosystem | Reason |
|------------|-----------|--------|
| `solana-program` | Cargo | Core Solana runtime interface |
| `anchor-lang` | Cargo | Smart contract framework |
| `anchor-spl` | Cargo | SPL token integration |
| `mobile-wallet-adapter-clientlib-ktx` | Gradle | MWA transaction signing |

## Proposing Major Upgrades

1. Create an issue describing the upgrade and expected breaking changes
2. Branch from `main`, apply the upgrade, and verify:
   - `anchor build` succeeds
   - `anchor test` passes (full suite, not just fast)
   - `./gradlew assembleDebug` succeeds
3. Open a PR with changelog notes
