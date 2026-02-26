# Solarma Vault — Test Cases

## Coverage Legend

| Status | Meaning |
|---|---|
| ✅ | Already implemented |
| 🆕 | To be implemented (Phase B) |

---

## 1. Scenario Tests (end-to-end lifecycle flows)

### S-1: Happy Wake — create → ack → claim ✅
> User creates alarm with deposit → alarm fires → user acknowledges → user claims back.
- Verify: Claimed status, owner balance restored, vault closed.
- Exists: `"Full lifecycle: create → snooze → ack_awake → claim"` (line 1219)

### S-2: Oversleep Slash — create → deadline → slash 🆕
> User creates alarm → doesn't wake up → third party slashes after deadline.
- Pre: create with Burn route, deposit = 0.1 SOL
- Wait: past deadline
- Action: third-party calls slash
- Assert: Slashed status, penalty_recipient received vault lamports, owner lost deposit

### S-3: Snooze Chain — create → snooze ×3 → ack → claim 🆕
> User creates alarm → snoozes 3 times → wakes up → claims remaining.
- Assert: remaining = initial − Σ(snooze_costs), each cost doubles
- Assert: alarm_time advanced by 3 × 300s = 900s
- Assert: deadline advanced by 900s
- Assert: snooze_count = 3

### S-4: Emergency Bail — create → emergency_refund 🆕
> User creates alarm → changes mind → emergency refund before alarm_time.
- Assert: Claimed status, owner received 95% of deposit + rent
- Assert: BURN_SINK received 5% penalty
- Assert: vault account closed

### S-5: Missed Ack → Slash — create → ack → deadline → slash 🆕
> User acknowledges but doesn't claim in time → gets slashed.
- Assert: Acknowledged → Slashed transition valid
- Verify: slash from Acknowledged state works (partially exists at line 2216)

### S-6: Snooze to Drain — create → snooze until vault drained → claim 🆕
> User snoozes repeatedly until remaining ≈ 0 → claims what's left.
- Key test: rent-exempt guard protects vault from GC
- Assert: eventually InsufficientDeposit stops snoozing
- Assert: claim still returns rent-exempt balance

---

## 2. Per-Instruction Negative Tests

### initialize
| ID | Test | Status |
|---|---|---|
| N-INIT-1 | Happy: creates profile | ✅ |
| N-INIT-2 | FAIL: double initialize (PDA collision) | ✅ |

### create_alarm
| ID | Test | Error | Status |
|---|---|---|---|
| N-CA-1 | Happy: no deposit | — | ✅ |
| N-CA-2 | Happy: with deposit | — | ✅ |
| N-CA-3 | Happy: Buddy route | — | ✅ |
| N-CA-4 | Happy: Donate route | — | ✅ |
| N-CA-5 | FAIL: alarm_time in past | AlarmTimeInPast | ✅ |
| N-CA-6 | FAIL: deadline < alarm_time | InvalidDeadline | ✅ |
| N-CA-7 | FAIL: deposit < MIN | DepositTooSmall | ✅ |
| N-CA-8 | FAIL: Buddy w/o destination | PenaltyDestinationRequired | ✅ |
| N-CA-9 | FAIL: Donate w/o destination | PenaltyDestinationRequired | ✅ |
| N-CA-10 | FAIL: invalid route (99) | InvalidPenaltyRoute | ✅ |
| N-CA-11 | FAIL: PDA collision (dup ID) | — | ✅ |

### ack_awake
| ID | Test | Error | Status |
|---|---|---|---|
| N-ACK-1 | Happy: Created → Acknowledged | — | ✅ |
| N-ACK-2 | FAIL: before alarm_time | TooEarly | ✅ |
| N-ACK-3 | FAIL: after deadline | DeadlinePassed | ✅ |
| N-ACK-4 | FAIL: double ack | InvalidAlarmState | ✅ |
| N-ACK-5 | FAIL: non-owner | ConstraintHasOne | ✅ |
| N-ACK-6 | FAIL: on Claimed alarm | InvalidAlarmState | ✅ |
| N-ACK-7 | FAIL: on Slashed alarm | InvalidAlarmState | ✅ |

### claim
| ID | Test | Error | Status |
|---|---|---|---|
| N-CL-1 | Happy: from Created | — | ✅ |
| N-CL-2 | Happy: from Acknowledged | — | ✅ |
| N-CL-3 | FAIL: before alarm_time | TooEarly | ✅ |
| N-CL-4 | FAIL: after deadline | DeadlinePassed | ✅ |
| N-CL-5 | FAIL: non-owner | ConstraintHasOne | ✅ |
| N-CL-6 | FAIL: double claim | InvalidAlarmState | ✅ |

### snooze
| ID | Test | Error | Status |
|---|---|---|---|
| N-SN-1 | Happy: first snooze (10%) | — | ✅ |
| N-SN-2 | Happy: cost doubles | — | ✅ |
| N-SN-3 | FAIL: before alarm_time | TooEarly | ✅ |
| N-SN-4 | FAIL: after deadline | DeadlinePassed | ✅ |
| N-SN-5 | FAIL: wrong expected count (H1) | InvalidAlarmState | ✅ |
| N-SN-6 | FAIL: non-owner | ConstraintHasOne | ✅ |
| N-SN-7 | FAIL: wrong sink address | InvalidSinkAddress | ✅ |
| N-SN-8 | FAIL: on Claimed alarm | InvalidAlarmState | ✅ |
| N-SN-9 | FAIL: on Acknowledged alarm | InvalidAlarmState | ✅ |
| N-SN-10 | FAIL: max snoozes reached | MaxSnoozesReached | 🆕 *(hard: vault drains first)* |

### slash
| ID | Test | Error | Status |
|---|---|---|---|
| N-SL-1 | Happy: Burn route | — | ✅ |
| N-SL-2 | Happy: Buddy route | — | ✅ |
| N-SL-3 | Happy: Donate route | — | ✅ |
| N-SL-4 | Happy: from Acknowledged | — | ✅ |
| N-SL-5 | FAIL: before deadline | DeadlineNotPassed | ✅ |
| N-SL-6 | FAIL: wrong recipient (Burn) | InvalidPenaltyRecipient | ✅ |
| N-SL-7 | FAIL: on Slashed alarm | InvalidAlarmState | ✅ |
| N-SL-8 | Happy: third-party slashes | — | ✅ |
| N-SL-9 | FAIL: wrong recipient for Buddy | InvalidPenaltyRecipient | ✅ |

### emergency_refund
| ID | Test | Error | Status |
|---|---|---|---|
| N-ER-1 | Happy: 5% penalty | — | ✅ |
| N-ER-2 | FAIL: after alarm_time | TooLateForRefund | ✅ |
| N-ER-3 | FAIL: wrong sink | InvalidSinkAddress | ✅ |
| N-ER-4 | FAIL: non-owner | ConstraintHasOne | ✅ |
| N-ER-5 | FAIL: on Acknowledged alarm | InvalidAlarmState | ✅ |
| N-ER-6 | FAIL: on Claimed alarm | InvalidAlarmState | ✅ |

---

## 3. Model-Based / State-Machine Test

### M-1: Random Action Sequence with Invariant Checking ✅

**Goal:** Generate random sequences of valid + invalid instructions, execute against a local validator, and verify all invariants hold.

**Design:**
```
for trial in 1..50:
    create_alarm(random deposit, random penalty_route)
    for step in 1..20:
        action = random_choice([ack, claim, snooze, slash, refund, wait])
        try execute(action)
        check_invariants(alarm_account):
            I-BAL-1: remaining ≤ initial
            I-BAL-2: vault.lamports ≥ rent_exempt_minimum
            I-STATE-1: if terminal → no more state changes
            I-STATE-2: snooze_count ≤ 10
            I-TIME-1: alarm_time < deadline
```

**Implementation approach:**
- TypeScript test with randomized action selection
- Seed-based RNG for reproducibility (`Math.seedrandom`)
- After each action: fetch alarm account, assert invariants
- Log full trace on failure for debugging
- Exists: `tests/solarma_vault_model.ts` (`@slow`)

---

## 4. Property-Based Tests 🆕

### P-1: Snooze Cost Reference Model (Rust)
```rust
/// For any (remaining, snooze_count) where remaining > 0 and count < 10:
///   on_chain_cost == reference_cost
///   reference_cost = min(remaining, remaining * 10 / 100 * 2^count)
fn prop_snooze_cost_matches_reference(remaining: u64, count: u8)
```

### P-2: Emergency Penalty Reference Model (Rust)
```rust
/// For any remaining amount:
///   penalty = remaining * 5 / 100
///   returned = remaining - penalty
///   returned + penalty == remaining (no loss)
fn prop_emergency_penalty_conservation(remaining: u64)
```

### P-3: Balance Conservation (TypeScript — on-chain)
```typescript
/// After every snooze:
///   old_remaining - new_remaining == lamports_sent_to_sink
/// After claim:
///   owner_balance_delta ≈ vault_lamports_before (within tx fee)
/// After slash:
///   penalty_recipient_delta ≈ vault_lamports_before
```

---

## 5. Summary — Remaining Phase B Coverage

| Category | Count | Tests |
|---|---|---|
| **Scenario** | 5 | S-2, S-3, S-4, S-5, S-6 |
| **Negative** | 1 | N-SN-10 |
| **Model-based** | 0 | Implemented (`M-1`) |
| **Property** | 3 | P-1, P-2, P-3 |
| **Total remaining** | **9** | |

**Estimated CI impact:** +2-3 minutes (scenario tests use short timers, model test runs 50 trials with 1-2s each)
