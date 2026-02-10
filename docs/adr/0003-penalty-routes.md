# ADR-0003: Penalty Routes (Burn / Donate / Buddy)

**Status:** Accepted  
**Date:** 2026-01-28  
**Author:** sapirl7

## Context

When a user is penalized (slash, snooze cost, emergency refund penalty), the
funds must go somewhere. The choice of destination shapes user motivation and
product positioning.

## Decision

Three penalty routes, chosen at alarm creation time:

| Route | Destination | Use case |
|-------|------------|----------|
| **Burn** | Fixed `BURN_SINK` address | "I lose it forever" — maximum commitment |
| **Donate** | `alarm.penalty_destination` (charity wallet) | "At least it helps someone" |
| **Buddy** | `alarm.penalty_destination` (friend's wallet) | "My friend profits if I fail" — social accountability |

The route is **immutable after creation** — it cannot be changed by the owner.

## Rationale

- **Behavioral science.** Loss aversion is strongest when the loss feels most
  real. Burn = irreversible destruction. Buddy = social shame + friend gains.
  Donate = prosocial framing reduces anxiety.
- **Immutable route.** If the owner could change the route after creation,
  they could switch to a self-controlled wallet before the deadline. The route
  must be locked at creation.
- **Permissionless slash compatibility.** The slasher passes
  `penalty_recipient` — the program validates it matches the alarm's route.
  For Burn, it must equal `BURN_SINK`. For Donate/Buddy, it must equal
  `alarm.penalty_destination`.

## Consequences

- Snooze cost and emergency refund penalty ALWAYS go to `BURN_SINK`
  (regardless of route). Only the final slash uses the full route.
- The `penalty_destination` field is optional — required for Donate/Buddy,
  unused for Burn.
- If the charity/buddy wallet is compromised or abandoned, the penalty still
  works — the recipient just doesn't claim it.

## Alternatives Considered

- **Single route (burn only):** simpler but loses the social accountability
  dimension that makes Buddy so powerful for habit formation.
- **Revenue route (to protocol treasury):** rejected to maintain the
  non-custodial, trustless positioning. No protocol rent-seeking.
- **Dynamic route:** rejected for the self-redirection exploit described above.
