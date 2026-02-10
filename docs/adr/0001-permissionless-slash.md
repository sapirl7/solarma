# ADR-0001: Permissionless Slash

**Status:** Accepted  
**Date:** 2026-01-28  
**Author:** sapirl7

## Context

When an alarm's deadline passes and the owner hasn't claimed their deposit,
someone must trigger the penalty. Two options exist:

1. **Owner-only slash** — only the alarm owner can trigger it.
2. **Permissionless slash** — anyone can call `slash` after the deadline.

## Decision

We chose **permissionless slash**.

## Rationale

- **The whole point is commitment.** If only the owner can slash themselves,
  they can simply… not do it. The alarm loses its "skin in the game" property.
- **No off-chain relayer needed.** A permissionless design means any wallet
  (a friend, a bot, a random good samaritan) can enforce the deadline. This
  keeps the architecture serverless.
- **Incentive alignment.** Buddy-route penalties give the friend a direct
  incentive to call slash — they receive the deposit.
- **Solana economics.** The caller pays the ~5000 lamport transaction fee,
  which is negligible. For Buddy route, they profit from the penalty.

## Consequences

- Any signer can close someone else's vault after the deadline.
- The `penalty_recipient` must be validated against the alarm's route
  (Burn → BURN_SINK, Donate/Buddy → penalty_destination).
- UX: the app should surface "your alarm was slashed" even if the owner
  didn't initiate it.

## Alternatives Considered

- **Owner-only slash with timeout:** after N days of inaction, anyone can
  slash. Rejected as unnecessarily complex — the deadline IS the timeout.
- **Keeper network:** rejected for complexity and cost.
