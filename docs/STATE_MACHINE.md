# Solarma Vault — State Machine

This document describes the on-chain alarm lifecycle for
`programs/solarma_vault`, including valid transitions and the clock-based
conditions (time windows).

## States

- `Created`: alarm exists and is live.
- `Acknowledged`: owner has recorded on-chain wake acknowledgement (H3).
- `Claimed`: terminal, vault closed to owner.
- `Slashed`: terminal, vault closed to penalty recipient.

## Time Windows

Let `now = Clock::get()?.unix_timestamp`:

- Refund window: `now < alarm_time`
  - `emergency_refund` is allowed.
- Active window: `alarm_time <= now < deadline`
  - `ack_awake`, `snooze`, `claim` are allowed (subject to status/authority).
- Slash window: `now >= deadline`
  - `slash` is allowed (subject to status/routing).

Boundary conditions (important for tests):

- At `now == alarm_time`: `claim/ack_awake/snooze` are allowed.
- At `now == deadline`: `slash` is allowed, `claim/ack_awake/snooze` are not.

## Transitions (Mermaid)

```mermaid
stateDiagram-v2
    [*] --> Created: create_alarm

    Created --> Acknowledged: ack_awake\n(now >= alarm_time && now < deadline)\n(owner)

    Created --> Created: snooze(expected_snooze_count)\n(now >= alarm_time && now < deadline)\n(owner, snooze_count < MAX)\n(expected == snooze_count)\n(+time, -deposit)

    Created --> Claimed: claim\n(now >= alarm_time && now < deadline)\n(owner, close vault -> owner)
    Acknowledged --> Claimed: claim\n(now >= alarm_time && now < deadline)\n(owner, close vault -> owner)

    Created --> Slashed: slash\n(now >= deadline)\n(anyone, close vault -> penalty_recipient)
    Acknowledged --> Slashed: slash\n(now >= deadline)\n(anyone, close vault -> penalty_recipient)

    Created --> Claimed: emergency_refund\n(now < alarm_time)\n(owner, penalty -> BURN_SINK,\nclose vault -> owner)

    Claimed --> Claimed: terminal (absorbing)
    Slashed --> Slashed: terminal (absorbing)
```

## Side Effects Summary

- `create_alarm`
  - Initializes `Alarm` + `Vault` PDAs.
  - Optional: transfers `deposit_amount` lamports from owner to vault.
- `snooze`
  - Deducts an exponential penalty from the vault to `BURN_SINK` (rent-guarded).
  - Increments `snooze_count`.
  - Extends both `alarm_time` and `deadline` by `DEFAULT_SNOOZE_EXTENSION_SECONDS`.
- `ack_awake`
  - Only records state (`Created -> Acknowledged`), no fund movement.
- `claim`
  - Closes the vault to the owner (returns deposit + rent).
  - Sets status to `Claimed` and clears `remaining_amount`.
- `slash`
  - Validates penalty recipient based on route.
  - Closes the vault to the penalty recipient (transfers deposit + rent).
  - Sets status to `Slashed` and clears `remaining_amount`.
- `emergency_refund`
  - Charges a percent penalty to `BURN_SINK` (rent-guarded).
  - Closes the vault to the owner.
  - Sets status to `Claimed` and clears `remaining_amount`.

