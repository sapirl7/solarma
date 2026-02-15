# Contributing to Solarma

Thank you for your interest in contributing! Solarma is a community-driven project for the Solana Seeker ecosystem.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Security](#security)

---

## Code of Conduct

By participating, you agree to uphold a welcoming, inclusive environment. Be respectful, assume good intent, and focus on constructive feedback.

---

## Getting Started

### Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| **Rust** | stable (latest) | [rustup.rs](https://rustup.rs) |
| **Anchor CLI** | 0.32.1 | `avm install 0.32.1` |
| **Solana CLI** | 2.3.0 (Agave) | [release.anza.xyz](https://release.anza.xyz) |
| **Node.js** | 20+ | [nodejs.org](https://nodejs.org) |
| **JDK** | 21 | [Adoptium](https://adoptium.net) |
| **Android Studio** | Hedgehog+ | [developer.android.com](https://developer.android.com/studio) |

Canonical versions: [`docs/TOOLCHAIN.md`](docs/TOOLCHAIN.md).

### Fork & Clone

```bash
git clone https://github.com/<you>/solarma.git
cd solarma
git remote add upstream https://github.com/sapirl7/solarma.git
```

### Build & Verify

```bash
# Smart contract
cd programs/solarma_vault
cargo fmt --check && cargo clippy --all-targets -- -D warnings
cargo test          # 140 unit tests
anchor test         # 70 integration tests

# Android
cd apps/android
./gradlew assembleDebug
./gradlew ktlintCheck
```

---

## Project Structure

```text
solarma/
├── programs/solarma_vault/        # Anchor smart contract (Rust)
│   ├── src/
│   │   ├── instructions/          # On-chain instruction handlers
│   │   ├── helpers.rs             # Pure business logic (unit-testable)
│   │   ├── state.rs               # Account data structures & enums
│   │   ├── constants.rs           # Protocol constants
│   │   ├── error.rs               # Custom error codes
│   │   ├── events.rs              # Event definitions for indexers
│   │   └── tests.rs               # Unit tests
│   └── tests/solarma_vault.ts     # Integration tests
├── apps/android/                  # Native Android app (Kotlin + Compose)
│   ├── app/src/main/              # Application source
│   └── app/src/androidTest/       # Instrumented tests
├── docs/                          # Documentation & assets
└── .github/workflows/ci.yml       # CI pipeline
```

---

## Development Workflow

### Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Stable — always deployable, protected |
| `gh-pages` | GitHub Pages (pitch deck) |
| `feature/*` | Feature branches (from `main`) |
| `fix/*` | Bug fixes (from `main`) |

### Flow

```text
fork → branch → make pre-commit → code + tests → fmt + clippy → push → PR → CI ✅ → review → squash merge
```

### Pre-commit Hooks

```bash
pip install pre-commit
make pre-commit
# or manually:
pre-commit install --hook-type pre-commit --hook-type commit-msg
```

This installs hooks for `cargo fmt`, `ktlintCheck`, markdownlint, and conventional commit message validation.

---

## Coding Standards

### Rust (Smart Contract)

- **`cargo fmt`** — mandatory (CI enforced)
- **`cargo clippy -- -D warnings`** — zero warnings policy
- **Arithmetic** — all math must use `checked_*` operations; no panics on-chain
- **Documentation** — public functions require `///` doc comments
- **Naming** — `snake_case` functions, `PascalCase` types

### Kotlin (Android)

- **ktlint** — mandatory (CI enforced via Gradle plugin)
- **Architecture** — MVVM with Hilt dependency injection
- **Compose** — follow Compose best practices (`remember`, `LaunchedEffect`)

### Commit Messages

[Conventional Commits](https://www.conventionalcommits.org/) format:

```text
<type>(<scope>): <description>
```

| Type | When |
|------|------|
| `feat` | New feature |
| `fix` | Bug fix |
| `test` | Adding or updating tests |
| `docs` | Documentation only |
| `refactor` | Code change that doesn't fix bug or add feature |
| `style` | Formatting, no code change |
| `chore` | Build, CI, deps |
| `ci` | CI configuration |

**Scopes**: `vault`, `android`, `ci`, `docs`, `deps`

**Examples**:

```text
feat(vault): add SPL token support for deposits
fix(android): resolve MWA timeout on Seeker device
test(vault): add overflow edge cases for snooze cost
```

---

## Testing

### What to Test

| Change | Required Tests |
|--------|---------------|
| New helper function | Unit test in `tests.rs` |
| New instruction | Integration test in `solarma_vault.ts` |
| New UI screen | Instrumented test in `androidTest/` |
| Edge case fix | Regression test with boundary values |

### Running Tests

```bash
# Smart contract unit tests (fast, no validator)
cargo test

# Integration tests (starts local validator)
anchor test

# Coverage report
cargo tarpaulin --out html

# Android
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented (needs device/emulator)
```

---

## Submitting Changes

### PR Checklist

- [ ] `cargo fmt --check` passes
- [ ] `cargo clippy --all-targets -- -D warnings` — zero warnings
- [ ] `cargo test` — all tests pass
- [ ] `anchor test` — all tests pass (if contract changed)
- [ ] `./gradlew ktlintCheck` passes (if Android changed)
- [ ] New code includes tests
- [ ] PR description explains **what** and **why**

### Review Process

1. All PRs require **one approval**
2. CI must be green before merge
3. Squash merge for clean history

---

## Security

**Do NOT open public issues for security vulnerabilities.**

See [SECURITY.md](SECURITY.md) for responsible disclosure process.

---

<p align="center">
  <sub>Thank you for helping make Solarma better 🌞</sub>
</p>
