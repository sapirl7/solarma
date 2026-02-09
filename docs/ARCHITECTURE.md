# Solarma Architecture

## Overview

Solarma is a two-component system:
1. **Android App** — Alarm management, wake proof verification, wallet interaction
2. **Solana Program** — Commitment vault with deposit/claim/slash logic

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         ANDROID APP                             │
├───────────────┬─────────────────┬──────────────┬───────────────┤
│  Alarm Engine │   Wake Proof    │   Wallet UI  │  Local Store  │
│  (Foreground  │   (Sensors)     │   (MWA)      │   (Room)      │
│   Service)    │                 │              │               │
└───────────────┴─────────────────┴──────────────┴───────────────┘
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                      SOLANA PROGRAM                             │
├───────────────┬─────────────────┬──────────────┬───────────────┤
│ create_alarm  │     claim       │    snooze    │    slash      │
└───────────────┴─────────────────┴──────────────┴───────────────┘
```

## Android Architecture

### Layers
- **UI Layer**: Jetpack Compose screens
- **Domain Layer**: Use cases for alarm, wake proof, wallet
- **Data Layer**: Repositories, Room DB, DataStore

### Key Components

#### Alarm Engine
- `AlarmManager.setExactAndAllowWhileIdle()` for precise timing
- `ForegroundService` during active challenge
- `BroadcastReceiver` for BOOT_COMPLETED (restore alarms)
- Full-screen Activity over lock screen

#### Wake Proof
- Step counter (primary) + accelerometer (fallback)
- NFC reader for tag verification
- QR scanner as alternative to NFC

#### Wallet Integration
- Mobile Wallet Adapter (MWA) for transaction signing
- Intent-based transaction queue (rebuild tx with fresh blockhash)

## Solana Program Architecture

### State Machine
```
Created → Acknowledged → Claimed   (wake proof → ack_awake → claim deposit)
        ↘ Slashed                   (deadline passed, penalty applied)
        ↘ Refunded                  (emergency refund before alarm, 5% penalty)
```

### Accounts
- `UserProfile` (PDA) — Reserved for future on-chain tag binding (currently tags stored locally)
- `Alarm` (PDA) — Alarm state, deposit, penalty route

### Instructions
| Instruction | Access | Description |
|-------------|--------|-------------|
| initialize | user | Create user profile PDA |
| create_alarm | user | Create alarm with deposit |
| ack_awake | user | Record wake proof completion on-chain |
| claim | user | Claim remaining deposit |
| snooze | user | Decrease deposit, extend time |
| emergency_refund | user | Cancel before alarm time (penalty applies) |
| slash | permissionless | Transfer deposit after deadline |

## Security Model

### On-Device
- Wallet keys never touch app (MWA isolation)
- Sensor data processed locally, never uploaded
- No PII in logs

### On-Chain
- Time-locked claims (before deadline only)
- Permissionless slash (anyone can trigger after deadline)
- Immutable penalty route (set at creation)
