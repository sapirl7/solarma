# Solarma — Tech Context

## Toolchain Versions

### Rust / Solana
| Tool | Version | Notes |
|------|---------|-------|
| Rust | stable (1.75+) | via rust-toolchain.toml |
| Anchor CLI | ^0.29.0 | `cargo install --git https://github.com/coral-xyz/anchor avm` |
| Solana CLI | ^1.18.0 | For local testing |
| Node.js | ^18.0.0 | Required for Anchor TS tests |

### Android
| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | Gradle toolchains |
| Kotlin | 1.9.x | AGP 8.x compatible |
| Android Gradle Plugin | 8.2.x | Latest stable |
| compileSdk | 34 | Android 14 |
| minSdk | 26 | Android 8.0 (Oreo) |
| targetSdk | 34 | Current target |

### Key Dependencies (Android)
- Jetpack Compose: UI layer
- Mobile Wallet Adapter: Solana wallet connection
- Hilt: Dependency injection
- Room: Local persistence
- DataStore: Preferences

### Key Dependencies (Anchor)
- anchor-lang: Program framework
- anchor-spl: SPL token utilities

---

## Environment Requirements

### Development Machine
- macOS / Linux preferred
- Android Studio or IntelliJ with Kotlin/Android plugins
- Rust toolchain via rustup

### CI Environment
- GitHub Actions
- Android SDK command-line tools
- Rust stable toolchain
- Node.js for Anchor tests

---

## Secrets Management

Never commit:
- `.env` files with real keys
- Wallet keypairs
- API tokens

Use:
- GitHub Secrets for CI
- Local `.env.local` (gitignored)
- Android BuildConfig for non-sensitive config
