# Toolchain

This project uses pinned tool versions for repeatable builds. Source of truth:
- Rust: `rust-toolchain.toml`
- Solana + Anchor: `programs/solarma_vault/Anchor.toml`

## Versions

| Component | Version |
|-----------|---------|
| Rust | stable (see `rust-toolchain.toml`) |
| Anchor CLI | 0.32.1 |
| Solana CLI | 2.3.0 (Agave) |
| Node.js | 20.x (LTS) |
| Java | 21 |
| Kotlin | 1.9.x |
| Android Gradle Plugin | 8.2.x |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 26 |

## Notes
- Use `avm install 0.32.1 && avm use 0.32.1` for Anchor.
- Solana CLI 2.3.0 (Agave) must match `Anchor.toml`. Install via `agave-install init 2.3.0`.
