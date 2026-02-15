# Solarma Runbook

## Quick Links

- [Setup](#setup)
- [Development](#development)
- [Troubleshooting](#troubleshooting)
- [Deployment](#deployment)

## Setup

### Prerequisites

See `docs/TOOLCHAIN.md` for the canonical version list.

1. **Rust toolchain**: `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`
2. **Anchor CLI**: `cargo install --git https://github.com/coral-xyz/anchor avm && avm install 0.32.1 && avm use 0.32.1`
3. **Solana CLI**: `agave-install init 2.3.0`
4. **Node.js 18+**: Use nvm or direct install
5. **Android Studio** or SDK command-line tools
6. **JDK 21**

### Initialize Project

```bash
cd solarma
make init
```

## Development

### Daily Commands

```bash
make lint    # Check code style
make test    # Run all tests
make build   # Build everything
make audit   # Security checks
```

### Running Android App

```bash
cd apps/android
./gradlew installDebug
```

### Testing Anchor Program

```bash
cd programs/solarma_vault
anchor test
```

## Troubleshooting

### Android Alarm Not Firing

1. Check battery optimization settings
2. Verify `USE_EXACT_ALARM` permission
3. Check logcat for AlarmManager events

### Anchor Build Fails

1. Verify `solana-cli` version matches `Anchor.toml`
2. Run `anchor clean` before rebuild
3. Check `rust-toolchain.toml` version

### Transaction Fails

1. Check network connectivity
2. Verify wallet has sufficient SOL for fees
3. Review transaction simulation logs

## Deployment

### Android Release Build

```bash
cd apps/android
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/
```

### Anchor Program Deployment

```bash
cd programs/solarma_vault
anchor build
anchor deploy --provider.cluster devnet
```

## Monitoring

### Logs Location

- Android: logcat with tag `Solarma`
- Anchor: Transaction explorer (Solscan, Solana Explorer)

### Quick Event Report (No Server)

```bash
node scripts/monitor_program_events.cjs --limit 200
```

### Key Metrics

- Alarm fire rate
- Wake proof completion rate
- Transaction success rate
