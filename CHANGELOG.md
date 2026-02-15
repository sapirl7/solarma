# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0](https://github.com/sapirl7/solarma/compare/v0.2.4...v0.3.0) (2026-02-15)


### Features

* extract ErrorClassifier for retryable vs non-retryable tx errors ([64b5da7](https://github.com/sapirl7/solarma/commit/64b5da78315004eb64abea97242e1c74d4addef0))
* fuzz tests, formal invariants, state machine spec, operational docs ([1876ef1](https://github.com/sapirl7/solarma/commit/1876ef128002896b34509074b4d80e32ae13cac3))
* maximize code coverage with helpers.rs + 66 unit tests ([4a55c55](https://github.com/sapirl7/solarma/commit/4a55c55a61e636bc0b74258f01ca8bc3f8ee3411))
* PR-1 protocol hardening + PR-2 Android reliability ([a413721](https://github.com/sapirl7/solarma/commit/a41372152b0972d1e5fd4671ba610e6e70da8fe5))
* supply-chain hardening + 5 architecture decision records (ADRs) ([0dec6fe](https://github.com/sapirl7/solarma/commit/0dec6fe6d6aaddcce08b52fcfd75c7a40a9756de))


### Bug Fixes

* **android:** bump toolchain for compileSdk 36 + fix lint errors\n\n- AGP 8.9.0 → 8.9.1 (required by AndroidX deps)\n- Kotlin 2.1.0 → 2.3.0 (required by navigation 2.9.7 transitive)\n- compileSdk/targetSdk 35 → 36 (required by core-ktx 1.17.0+)\n- Migrate kotlinOptions to compilerOptions DSL (Kotlin 2.3 breaking)\n- Fix strings.xml positional format (%.4f → %1$.4f)\n- Add org.json:json test dep for TransactionSnapshotTest" ([678af09](https://github.com/sapirl7/solarma/commit/678af093f84ff081aeaaf667cbe745bf97f1cd24))
* **android:** disable ktlint argument-list-wrapping rule ([5f37a10](https://github.com/sapirl7/solarma/commit/5f37a10ef5fa3b8483571e68da1e448e30a8638e))
* **android:** disable ktlint rules conflicting with Compose/test patterns ([1246b04](https://github.com/sapirl7/solarma/commit/1246b04a51ef164bcedffd9690bacf52d8150298))
* **android:** fix Int overflow in EconomicInvariantsTest ([cfb026c](https://github.com/sapirl7/solarma/commit/cfb026c97bf95c724feb76126d9c6d5274115211))
* **android:** properly fix ktlint violations across all sources ([9bc439b](https://github.com/sapirl7/solarma/commit/9bc439be31649da4898d7afb8837de5211c7af6f))
* CI failures — buddy-only slash test caller + HistoryViewModelTest dispatcher ([7efd2de](https://github.com/sapirl7/solarma/commit/7efd2de326aa7af6bb1b74d06eff7a732109214f))
* **ci:** fix ktlint parse crashes and remaining lint issues ([4bf453e](https://github.com/sapirl7/solarma/commit/4bf453e0c284b7f8073f325ba07aa76234a6d0e3))
* **ci:** resolve clippy errors, add retry for flaky KSP ([6409ebb](https://github.com/sapirl7/solarma/commit/6409ebbd045fb86cf1778f1ce3f2a530f5816ec7))
* **ci:** resolve ktlint and markdownlint pre-existing violations ([40da0b9](https://github.com/sapirl7/solarma/commit/40da0b9e8b95d5113cf35cf6ef688232a3157389))
* **deps:** pin Hilt to 2.58 for AGP 8.x compatibility ([2af7a7a](https://github.com/sapirl7/solarma/commit/2af7a7ac65cb5bea90bfe12bb0f105ddab71b0e1))
* resolve CI failures (cargo fmt, ktlint) ([2a3df63](https://github.com/sapirl7/solarma/commit/2a3df636737179a56a696708e0d979aae175fa24))
* slash buddy test, add ktlint + instrumented smoke test ([1e1933b](https://github.com/sapirl7/solarma/commit/1e1933b726e3e84f2ef9d9a3aa94f30da294ec9d))
* **test:** HistoryViewModelTest — activate WhileSubscribed collector ([0a99591](https://github.com/sapirl7/solarma/commit/0a995910dc88a2e75ee4bdc8f97236118f784bcf))
* **test:** relax message header assertion in TransactionBuilderTest\n\nnumReadOnlyNonSigners may be 1 (just program ID) depending on\nhow the system program is categorized in the account table." ([7298abd](https://github.com/sapirl7/solarma/commit/7298abde96711f8360e34491c4434e87825d9d5e))
* **vault:** remove module_inception wrapper from prop_tests ([bf4cd23](https://github.com/sapirl7/solarma/commit/bf4cd236d151ac69025ffdbc33304ec0175f3892))

## [Unreleased]

### Added

- **`sweep_acknowledged`** instruction — permissionless return-to-owner for acknowledged alarms after claim grace
- **Claim grace window** — `CLAIM_GRACE_SECONDS` (120s) extends claim deadline for acknowledged alarms
- **Buddy-only slash window** — `BUDDY_ONLY_SECONDS` (120s) gives buddy exclusive first-right to slash
- **`BuddyOnlyWindow`** error code for buddy-exclusive slash enforcement
- **140 Rust unit tests** (up from 22), **70 Anchor TS integration tests** (up from 61)

### Changed

- `claim` now requires `Acknowledged` status (was `Created | Acknowledged`)
- `slash` now requires `Created` status only (was `Created | Acknowledged`)
- Documentation synced across 6 files to match actual protocol (STATE_MACHINE, ARCHITECTURE, TECHNICAL_SPEC, RUNBOOK, TESTING_STRATEGY, program README)

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
[0.2.3]: https://github.com/sapirl7/solarma/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/sapirl7/solarma/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/sapirl7/solarma/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/sapirl7/solarma/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sapirl7/solarma/releases/tag/v0.1.0
