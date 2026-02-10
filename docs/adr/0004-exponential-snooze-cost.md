# ADR-0004: Exponential Snooze Cost

**Status:** Accepted  
**Date:** 2026-01-28  
**Author:** sapirl7

## Context

Users can "snooze" their alarm to delay it, but unlimited free snoozing
defeats the purpose. The cost model must make each snooze progressively
more painful while keeping the first one affordable.

## Decision

Snooze cost follows an exponential formula:

```
cost = (remaining_amount × 10 / 100) × 2^snooze_count
```

- First snooze: 10% of remaining
- Second snooze: 20% of remaining (after first deduction)
- Third snooze: 40% of remaining
- Maximum: `MAX_SNOOZE_COUNT = 3` (configurable)

All snooze costs go to `BURN_SINK`, regardless of penalty route.

## Rationale

- **Exponential = credible deterrent.** Linear costs (10% each time) don't
  feel progressively worse. With exponential, snooze 3 costs 4× the base —
  users feel the acceleration.
- **Capped at MAX_SNOOZE_COUNT.** Prevents the cost from overflowing and
  ensures the alarm eventually reaches its deadline.
- **Idempotent via `expected_snooze_count`.** The client passes the current
  count. If it doesn't match on-chain state, the tx fails — preventing
  accidental double-snooze from transaction replays.
- **Rent-exempt guard.** The actual cost is `min(calculated_cost, available)`
  where `available = vault_lamports - rent_minimum`. This prevents the vault
  from being garbage-collected by falling below rent exemption.

## Consequences

- After 3 snoozes, the user has burned ~70% of their deposit to penalties.
  The remaining deposit is still claimable or slashable.
- The time extension per snooze (`DEFAULT_SNOOZE_EXTENSION_SECONDS`) is
  fixed, not proportional to cost. Both `alarm_time` and `deadline` shift
  by the same amount.
- The formula is implemented in `helpers::snooze_cost()` and verified by
  property-based fuzz tests across thousands of random scenarios.

## Alternatives Considered

- **Linear cost (fixed 10%):** too predictable, doesn't escalate urgency.
- **Flat fee in SOL:** doesn't scale with deposit size — irrelevant for
  small deposits, punishing for large ones.
- **No snooze limit:** allows indefinite postponement, defeating the alarm.
