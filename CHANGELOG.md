# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.4] - 2026-02-10

### Added
- **`helpers.rs`** — extracted all pure business logic from instruction handlers into a standalone, unit-testable module:
  - `snooze_cost()` — exponential snooze cost calculation
  - `emergency_penalty()` — early cancellation penalty
  - `validate_alarm_params()` — alarm creation validation
  - `is_claim_window()` / `is_slash_window()` / `is_refund_window()` / `is_snooze_window()` — time window guards
  - `validate_penalty_recipient()` — penalty routing validation
  - `snooze_time_extension()` — alarm/deadline extension after snooze
  - `cap_at_rent_exempt()` — rent-exempt deduction capping
- **66 unit tests** covering all helpers, edge cases, overflow safety, boundary conditions, and full lifecycle simulations
- `codecov.yml` — coverage configuration with ignore rules for untestable Anchor boilerplate
- `CONTRIBUTING.md` — comprehensive contribution guide with toolchain setup, coding standards, and PR checklist
- `SECURITY.md` — security policy with responsible disclosure process, smart contract invariants, and audit status
- `.nojekyll` on `gh-pages` branch to fix GitHub Pages 404
- Android instrumented smoke test (`SolarmaAppSmokeTest.kt`)
- ktlint plugin for Kotlin code style enforcement

### Changed
- CI `cargo-tarpaulin` now excludes instruction handler files (Anchor account struct boilerplate) from coverage reports
- Slash buddy integration test fixed: corrected transaction signing pattern from `provider.sendAndConfirm(tx, [])` to `.rpc()`
- Repository quality scored from 4% coverage → estimated 60–80%+ on testable code

### Fixed
- GitHub Pages pitch deck returning 404 (missing `.nojekyll` file)

## [0.2.3] - 2026-02-08

### Fixed
- Stuck alarms from sync race condition

### Added
- Solarma logo on onboarding page 1
- Official Solana gradient on onboarding page 4
- Slash/resolve for expired alarms + smart deposit card
- 4-slide onboarding flow for first-time users

## [0.2.2] - 2026-02-08

### Added
- Dynamic `shields.io` badges in README
- `CODEOWNERS` with path-based rules
- `cargo-tarpaulin` coverage in CI with Codecov integration
- Concurrency groups in CI workflow

## [0.2.1] - 2026-02-08

### Fixed
- **Critical:** Successful `CREATE_ALARM` and `EMERGENCY_REFUND` transactions not appearing in Transaction History
- Truncated wallet public key now shown in wallet status card (first 4 + last 4 chars)
- Removed duplicate deposit indicator dot (`◎`) from alarm cards

### Changed
- `docs/TOOLCHAIN.md` updated: Java 17 → 21, compileSdk/targetSdk 34 → 35
- README: fixed non-existent `make deploy-dev` target, added actual Makefile commands

## [0.2.0] - 2026-02-03

### Added
- Onchain alarm import flow (Settings → Import)
- RPC endpoint overrides via Gradle properties (`SOLANA_RPC_DEVNET` / `SOLANA_RPC_MAINNET`)
- Exponential backoff for RPC fallback
- QA checklist and release checklist docs
- `OnchainAlarmParser` with unit tests
- Release signing support via `keystore.properties`

### Changed
- Donate route now requires `penalty_destination` (audit fix)
- `allowBackup` disabled for security
- Snooze penalty description fixed (exponential 10% → 20% → 40%)
- Roadmap updated with mainnet-ready criteria

### Removed
- `.ai/` internal docs from repository

## [0.1.0] - 2026-01-28

- Initial Devnet MVP
- Anchor smart contract with 7 instructions (initialize, create_alarm, claim, snooze, slash, emergency_refund, ack_awake)
- Native Android app with Jetpack Compose
- Mobile Wallet Adapter integration
- Wake proof challenges: NFC, QR, step counter
- Penalty routes: Burn, Donate, Buddy

---

[Unreleased]: https://github.com/sapirl7/solarma/compare/v0.2.4...HEAD
[0.2.4]: https://github.com/sapirl7/solarma/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/sapirl7/solarma/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/sapirl7/solarma/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/sapirl7/solarma/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/sapirl7/solarma/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sapirl7/solarma/releases/tag/v0.1.0
