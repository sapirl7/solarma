# Solarma — Technical Specification

## Overview

Solarma is a commitment-based alarm application built on Solana. This document provides technical details for auditors, developers, and integrators.

## Smart Contract (Anchor)

### Program ID (Devnet)
```
F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP
```
*Note: Mainnet Program ID will differ when deployed.*

### Account Structures

#### UserProfile Account
```rust
pub struct UserProfile {
    pub owner: Pubkey,          // 32 bytes
    pub tag_hash: Option<[u8; 32]>, // 33 bytes (reserved)
    pub bump: u8,               // 1 byte
}
```

#### Alarm Account
```rust
pub struct Alarm {
    pub owner: Pubkey,           // 32 bytes
    pub alarm_time: i64,         // 8 bytes - Unix timestamp
    pub deadline: i64,           // 8 bytes - Unix timestamp
    pub deposit_mint: Option<Pubkey>, // 33 bytes (SOL = None)
    pub initial_amount: u64,     // 8 bytes - lamports
    pub remaining_amount: u64,   // 8 bytes - lamports
    pub penalty_route: u8,       // 1 byte - 0=Burn, 1=Donate, 2=Buddy
    pub penalty_destination: Option<Pubkey>, // 33 bytes
    pub snooze_count: u8,        // 1 byte
    pub status: AlarmStatus,     // 1 byte - Created/Claimed/Slashed
    pub bump: u8,                // 1 byte
    pub vault_bump: u8,          // 1 byte
}
```

#### Vault Account
```rust
pub struct Vault {
    pub alarm: Pubkey,  // 32 bytes - Associated alarm
    pub bump: u8,       // 1 byte
}
```

### PDA Derivation

```rust
// Alarm PDA
seeds = ["alarm", owner.key(), alarm_id.to_le_bytes()]

// Vault PDA  
seeds = ["vault", alarm.key()]
```

### Instructions

#### initialize
Creates a user profile PDA (currently reserved for future on-chain tag binding).

**Accounts:**
1. `user_profile` (init) - UserProfile PDA
2. `owner` (signer, mut) - Profile owner
3. `system_program` - System program

#### create_alarm
Creates a new alarm with optional SOL deposit.

**Parameters:**
- `alarm_id: u64` - Unique identifier (typically timestamp)
- `alarm_time: i64` - When alarm should ring
- `deadline: i64` - When claim period ends
- `deposit_amount: u64` - Lamports to stake
- `penalty_route: u8` - Where deposit goes on failure
- `penalty_destination: Option<Pubkey>` - Required for Donate or Buddy routes

**Accounts:**
1. `alarm` (init) - Alarm PDA
2. `vault` (init) - Vault PDA
3. `owner` (signer, mut) - Alarm creator
4. `system_program` - System program

#### claim
Returns deposit to owner. Must be after `alarm_time` and before `deadline`.

**Accounts:**
1. `alarm` (mut) - Alarm account
2. `vault` (mut, close) - Vault, closed to owner
3. `owner` (signer, mut) - Must match alarm.owner
4. `system_program` - System program

**Errors:**
- `TooEarly` - Called before alarm_time
- `InvalidAlarmState` - Not in Created status

#### snooze
Deducts 10% from remaining deposit and doubles each use (10% → 20% → 40%...),
extends alarm_time and deadline by 5 minutes.

**Accounts:**
1. `alarm` (mut) - Alarm account
2. `vault` (mut) - Vault
3. `sink` (mut) - Where snooze fee goes (BURN_SINK)
4. `owner` (signer, mut) - Must match alarm.owner
5. `system_program` - System program

**Errors:**
- `TooEarly` - Called before alarm_time
- `MaxSnoozeReached` - Already snoozed 10 times
- `InsufficientDeposit` - <10% remaining

#### slash
Permissionless instruction. Transfers remaining deposit to penalty route.

**Accounts:**
1. `alarm` (mut) - Alarm account
2. `vault` (mut, close) - Vault, closed to penalty_recipient
3. `penalty_recipient` (mut) - Validated against route
4. `caller` (signer) - Anyone can call
5. `system_program` - System program

**Errors:**
- `DeadlineNotPassed` - Called before deadline
- `InvalidPenaltyRecipient` - Wrong recipient for route

#### emergency_refund
Owner cancels alarm before it rings. 5% fee applies.

**Accounts:**
1. `alarm` (mut) - Alarm account
2. `vault` (mut, close) - Vault, closed to owner
3. `sink` (mut) - Penalty sink (BURN_SINK)
4. `owner` (signer, mut) - Must match alarm.owner
5. `system_program` - System program

**Errors:**
- `TooLateForRefund` - Called after alarm_time

### Constants

```rust
pub const BURN_SINK: Pubkey = ...; // Well-known burn address
pub const DEFAULT_SNOOZE_PERCENT: u64 = 10;
pub const MAX_SNOOZE_COUNT: u8 = 10;
pub const MIN_DEPOSIT_LAMPORTS: u64 = 1_000_000; // 0.001 SOL
pub const EMERGENCY_REFUND_PENALTY_PERCENT: u64 = 5;
pub const DEFAULT_GRACE_PERIOD: i64 = 1800; // 30 minutes
pub const DEFAULT_SNOOZE_EXTENSION_SECONDS: i64 = 300; // 5 minutes
```

### Error Codes (Names)

- DeadlinePassed
- DeadlineNotPassed
- InvalidAlarmState
- InvalidPenaltyRoute
- InsufficientDeposit
- Overflow
- AlarmTimeInPast
- InvalidDeadline
- DepositTooSmall
- BuddyAddressRequired
- PenaltyDestinationRequired
- InvalidSinkAddress
- MaxSnoozesReached
- InvalidPenaltyRecipient
- PenaltyDestinationNotSet
- TooEarly
- TooLateForRefund

---

## Android Architecture

### Package Structure
```
app.solarma/
├── alarm/
│   ├── AlarmRepository.kt     # Data layer
│   ├── AlarmScheduler.kt      # AlarmManager wrapper
│   ├── AlarmService.kt        # Foreground service
│   ├── AlarmReceiver.kt       # Broadcast receiver
│   ├── AlarmActivity.kt       # Wake screen UI
│   ├── BootReceiver.kt        # Boot completed
│   └── RestoreAlarmsWorker.kt # WorkManager
├── wakeproof/
│   ├── WakeProofEngine.kt     # Challenge orchestration
│   └── StepCounter.kt         # Sensor handling
├── wallet/
│   ├── WalletManager.kt       # MWA integration
│   ├── SolarmaInstructionBuilder.kt # Anchor serialization
│   ├── TransactionBuilder.kt  # Tx assembly
│   ├── OnchainAlarmService.kt # Full flow
│   ├── SolanaRpcClient.kt     # RPC calls
│   └── TransactionQueue.kt    # Retry logic
├── data/local/
│   ├── SolarmaDatabase.kt     # Room DB
│   ├── AlarmEntity.kt         # Alarm table
│   └── AlarmDao.kt            # Queries
└── ui/
    ├── home/HomeScreen.kt     # Main screen
    └── create/CreateAlarmScreen.kt # Creation flow
```

### Key Dependencies
- **Jetpack Compose** - UI
- **Hilt** - DI
- **Room** - Local DB
- **WorkManager** - Reliable background work
- **sol4k** - Solana primitives
- **MWA** - Mobile Wallet Adapter

### Alarm Flow

```
1. User creates alarm
   └─> CreateAlarmViewModel.save()
       ├─> AlarmRepository.insert() → Room
       ├─> AlarmScheduler.schedule() → AlarmManager
       └─> OnchainAlarmService.createOnchainAlarm() → Solana (pending confirmation supported)

2. Alarm fires (AlarmManager)
   └─> AlarmReceiver.onReceive()
       └─> AlarmService.startForeground()
           └─> AlarmActivity (fullscreen)
               └─> WakeProofEngine.startChallenge()
                   └─> StepCounter.countSteps()

3. Challenge complete
   └─> WakeProofEngine.isComplete = true
       └─> AlarmActivity.dismissAlarm()
           └─> OnchainAlarmService.claimDeposit() → Solana
```

### Security Considerations

1. **AlarmManager Exact Alarms** - Uses `setExactAndAllowWhileIdle` for reliability
2. **Foreground Service** - Prevents process death during challenge
3. **WakeLock** - Keeps device awake during alarm
4. **Boot Restore** - WorkManager ensures alarms survive reboot
5. **MWA Security** - Transaction signing never exposes private keys

---

## API Reference

### RPC Endpoints (Default)

The app uses a fallback list of public endpoints:

**Devnet**
- `https://api.devnet.solana.com`
- `https://devnet.helius-rpc.com`
- `https://rpc-devnet.solflare.com`
- `https://devnet.genesysgo.net`

**Mainnet**
- `https://api.mainnet-beta.solana.com`
- `https://solana-mainnet.g.alchemy.com/v2/demo`
- `https://rpc.helius.xyz`

For production, use a dedicated provider with API keys.

### Transaction Structure

All transactions use Anchor's instruction format:
- First 8 bytes: Discriminator (sha256 of instruction name)
- Remaining bytes: Borsh-serialized parameters

---

## Testing

### Anchor Tests
```bash
anchor test
```

### Android Tests
```bash
./gradlew test
```

### Manual Testing Checklist
- [ ] Create alarm with deposit
- [ ] Complete step challenge
- [ ] Verify claim returns deposit
- [ ] Test snooze deduction
- [ ] Test emergency refund
- [ ] Test slash after deadline
- [ ] Test boot restore
