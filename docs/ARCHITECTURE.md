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
- Transaction queue for offline resilience

## Solana Program Architecture

### State Machine
```
Created → Claimed (success)
        ↘ Slashed (failure after deadline)
```

### Accounts
- `UserProfile` (PDA) — User settings, registered tags
- `Alarm` (PDA) — Alarm state, deposit, penalty route

### Instructions
| Instruction | Access | Description |
|-------------|--------|-------------|
| register_tag | user | Store hash of NFC/QR tag |
| create_alarm | user | Create alarm with deposit |
| snooze | user | Decrease deposit, extend time |
| claim | user | Claim remaining deposit |
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
