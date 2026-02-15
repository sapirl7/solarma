# Solarma Vault — On-Chain Commitment Vault

Solana program for the Solarma alarm app. Users deposit SOL when setting alarms and claim it back after completing their wake proof, or the deposit is slashed after the deadline.

## Program ID

```
F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP
```

**Cluster**: Devnet

## Architecture

| Account | Seeds | Description |
|---------|-------|-------------|
| `UserProfile` | `["user-profile", owner]` | Per-user profile with optional NFC tag hash |
| `Alarm` | `["alarm", owner, alarm_id]` | Alarm state (times, deposit, penalty config) |
| `Vault` | `["vault", alarm]` | SOL escrow holding the deposit |

## Instructions

| Instruction | Signer | Description |
|-------------|--------|-------------|
| `initialize` | Owner | Create user profile |
| `create_alarm` | Owner | Create alarm + vault with SOL deposit |
| `ack_awake` | Owner | Record wake proof (Created → Acknowledged) |
| `claim` | Owner | Return deposit after alarm time, before deadline |
| `snooze` | Owner | Pay penalty for extra time (exponential cost) |
| `emergency_refund` | Owner | Cancel alarm before alarm time (5% penalty) |
| `slash` | Anyone | Forfeit deposit after deadline (permissionless) |
| `sweep_acknowledged` | Anyone | Return ACKed deposit after claim grace (permissionless) |

## Penalty Routes

| Route | Value | Destination |
|-------|-------|-------------|
| Burn | 0 | Solana incinerator (`1nc1nerator...`) |
| Donate | 1 | User-specified charity address |
| Buddy | 2 | User-specified friend address |

## Build & Test

```bash
# Build
anchor build

# Run Rust unit tests (140 tests, instant)
cargo test

# Run integration tests against devnet (70 tests, ~20 min with timing tests)
ANCHOR_PROVIDER_URL=https://api.devnet.solana.com \
ANCHOR_WALLET=~/.config/solana/id.json \
npx ts-mocha -p tsconfig.json -t 500000 tests/solarma_vault.ts

# Clippy (zero warnings)
cargo clippy -- -D warnings
```

## Events

All alarm events include `alarm_id` for off-chain indexer correlation.

| Event | Emitted by |
|-------|-----------|
| `ProfileInitialized` | `initialize` |
| `AlarmCreated` | `create_alarm` |
| `AlarmClaimed` | `claim` |
| `AlarmSnoozed` | `snooze` |
| `AlarmSlashed` | `slash` |
| `EmergencyRefundExecuted` | `emergency_refund` |
| `WakeAcknowledged` | `ack_awake` |
| `SweepExecuted` | `sweep_acknowledged` |

## Security

- **Rent-exempt guards** in snooze and emergency_refund prevent vault account from being garbage-collected
- **Checked arithmetic** everywhere — all math uses `checked_*` operations
- **Idempotent snooze** (H1) — `expected_snooze_count` parameter prevents duplicate snoozing on retry
- **Permissionless slash** — anyone can trigger after deadline, validated against penalty recipient
- **Buddy-only window** — buddy gets a 120s exclusive slash window before permissionless opens
- **Claim grace window** — 120s grace after deadline for acknowledged alarms to claim
- **Sweep safety net** — permissionless return-to-owner after grace expires, no penalty
- **Time validation** — strict ordering: alarm_time < deadline, with appropriate guards on each instruction

## License

See repository root.
