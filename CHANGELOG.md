# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [Unreleased]

### Added
- Toolchain reference (`docs/TOOLCHAIN.md`)
- Threat model and security checks documentation
- Security audit script and `make audit` target
- Release build workflow and local release build script
- QA matrix template and support/community templates

### Changed
- CI toolchain aligned to Anchor 0.32.1 and Solana 1.18.26
- Standardized Anchor tests on npm

### Removed
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
