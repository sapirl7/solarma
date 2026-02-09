# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [Unreleased]

## [0.2.2] - 2026-02-08

### Fixed
- **Critical:** Successful `CREATE_ALARM` and `EMERGENCY_REFUND` transactions not appearing in Transaction History
- Truncated wallet public key now shown in wallet status card (first 4 + last 4 chars)
- Removed duplicate deposit indicator dot (`◎`) from alarm cards

### Changed
- `docs/TOOLCHAIN.md` updated: Java 17 → 21, compileSdk/targetSdk 34 → 35
- README: fixed non-existent `make deploy-dev` target, added actual Makefile commands

## [0.2.1] - 2026-02-08

### Added
- `PenaltyRouteDisplay` — shared utility mapping on-chain penalty route integers to emoji/labels (SSOT)
- `SnoozePenaltyDisplay` — exponential penalty calculator matching on-chain formula (10% × 2^n)
- 14 new unit tests: `PenaltyRouteDisplayTest` (6) + `SnoozePenaltyDisplayTest` (8)
- Full i18n: 25+ strings extracted to `strings.xml`, wired via `stringResource()`
- Compose `@Preview` functions for `AlarmCard` and `EmptyAlarmsCard`
- CI status badge in README
- KDoc on all public component APIs
- Toolchain reference (`docs/TOOLCHAIN.md`)
- Threat model and security checks documentation
- Security audit script and `make audit` target
- Release build workflow and local release build script
- QA matrix template and support/community templates

### Fixed
- **Critical:** Snooze penalty UI showed linear `n×10%` instead of exponential `10%×2^n` (matches on-chain)
- **Critical:** Deadline displayed 1 hour instead of 30 minutes (`AlarmTiming.GRACE_PERIOD_MILLIS`)
- RPC balance pre-check failures silently swallowed — now logged via `Log.w()`
- Accessibility: `contentDescription` added to SettingsRow icons and chevrons
- Deprecated `outlinedButtonBorder` replaced with `outlinedButtonBorder(enabled = true)`

### Changed
- All 16 deprecated color alias usages migrated to canonical names (0 warnings)
- CI JDK version 17 → 21 (matches app build requirements)
- `actions/cache` v3 → v4 in CI workflows
- Balance pre-check uses `connState.publicKeyBase58` directly (removed duplicate `toBase58`)
- Fee estimation extracted to named constant `ESTIMATED_FEES_SOL`
- CI toolchain aligned to Anchor 0.32.1 and Solana 1.18.26
- Standardized Anchor tests on npm
- Security audit configuration hardened and made deterministic
- Android/Anchor tasks now auto-skip when tooling is not available
- Pinned mocha to a non-vulnerable version for tests

### Removed
- 3 oversized AI-generated PNG icons (76KB total, unreferenced in code)
- Stray root `package-lock.json`

## [0.2.0] - 2026-02-03

### Added
- Onchain alarm import flow (Settings → Import)
- RPC endpoint overrides via Gradle properties (SOLANA_RPC_DEVNET/MAINNET)
- Exponential backoff for RPC fallback
- QA checklist and release checklist docs
- OnchainAlarmParser with unit tests
- Release signing support via keystore.properties

### Changed
- Donate route now requires penalty_destination (audit fix)
- allowBackup disabled for security
- SNOOZE penalty description fixed (exponential 10%→20%→40%)
- Roadmap updated with mainnet-ready criteria

### Removed
- .ai/ internal docs from repository

## [0.1.0]
- Initial Devnet MVP
