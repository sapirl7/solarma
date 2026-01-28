# Solarma

> Wake up and prove it. Your SOL depends on it.

A Solana-native Android alarm app with onchain commitment vault. Stake SOL when setting alarms — complete your wake proof challenge to claim it back, or lose it!

## 🎯 Overview

Solarma combines two powerful concepts:
1. **Physical Wake Proof**: Complete challenges (steps, NFC scan) to dismiss alarms
2. **Financial Commitment**: Stake SOL that you only get back if you wake up on time

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App                              │
├───────────────┬───────────────┬───────────────────────────────┤
│ Alarm Engine  │ Wake Proof    │ Wallet Integration             │
│ - Scheduler   │ - StepCounter │ - MWA (Mobile Wallet Adapter)  │
│ - Service     │ - NFC/QR      │ - Transaction Builder          │
│ - Repository  │ - Engine      │ - RPC Client                   │
└───────────────┴───────────────┴───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Solana (Anchor)                           │
├──────────────────────────────────────────────────────────────┤
│ Instructions:                                                 │
│ - create_alarm: Create alarm with SOL deposit                │
│ - claim: Return deposit after completing wake proof          │
│ - snooze: Reduce deposit for extra time                      │
│ - slash: Transfer to penalty after deadline (permissionless) │
│ - emergency_refund: Cancel before alarm time (5% fee)        │
└──────────────────────────────────────────────────────────────┘
```

## 🚀 Quick Start

### Prerequisites
- Node.js 18+
- Rust 1.70+
- Solana CLI
- Anchor 0.29+
- Android Studio (for Android app)

### Setup

```bash
# Clone the repo
git clone https://github.com/sapirl7/solarma.git
cd solarma

# Run setup script
./scripts/setup-dev.sh

# Deploy to devnet
./scripts/deploy-devnet.sh
```

### Run Tests

```bash
# Anchor tests
anchor test

# Android unit tests
cd apps/android
./gradlew test
```

## 📱 Android App

The Android app is built with:
- **Kotlin** + **Jetpack Compose** for UI
- **Hilt** for dependency injection
- **Room** for local persistence
- **WorkManager** for reliable alarm restoration
- **sol4k** for Solana primitives
- **Mobile Wallet Adapter** for wallet integration

### Key Components

| Component | Description |
|-----------|-------------|
| `AlarmRepository` | Manages alarm data in Room DB |
| `AlarmScheduler` | Schedules alarms via AlarmManager |
| `WakeProofEngine` | Orchestrates challenge completion |
| `StepCounter` | Counts steps using TYPE_STEP_COUNTER |
| `OnchainAlarmService` | Bridges local alarms with Solana |

## ⚓ Anchor Program

The Solana program handles the financial commitment:

### Instructions

| Instruction | Description |
|-------------|-------------|
| `create_alarm` | Create alarm PDA with SOL deposit to vault |
| `claim` | Return deposit to owner (after alarm_time, before deadline) |
| `snooze` | Deduct 10% from deposit, extend deadline |
| `slash` | Transfer remaining deposit to penalty route (after deadline) |
| `emergency_refund` | Cancel alarm before alarm_time (5% fee) |

### PDAs

- **Alarm PDA**: `["alarm", owner, alarm_id]`
- **Vault PDA**: `["vault", alarm_pda]`

## 💰 Penalty Routes

When you fail to wake up, your deposit goes to:

| Route | Description |
|-------|-------------|
| 🔥 Burn | Permanently burned |
| 🎁 Donate | Sent to charity |
| 👋 Buddy | Sent to a friend |

## 🔒 Security

- Vault PDA is program-owned (no external access)
- Time checks prevent early claim/snooze
- Close constraints ensure proper fund transfers
- Emergency refund has 5% fee to prevent abuse

## 📄 License

MIT

## 🙏 Acknowledgments

Built for the Solana ecosystem with ❤️
