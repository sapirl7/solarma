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
  - `ack_awake`, `snooze` are allowed (subject to status/authority).
- Claim grace window: `alarm_time <= now <= deadline + CLAIM_GRACE_SECONDS`
  - `claim` is allowed only from `Acknowledged`.
- Slash window: `now >= deadline`
  - `slash` is allowed only from `Created` (subject to routing).
- Sweep window: `now > deadline + CLAIM_GRACE_SECONDS`
  - `sweep_acknowledged` is allowed from `Acknowledged`.

Buddy route subwindow:

- For `PenaltyRoute::Buddy` only:
  `deadline <= now < deadline + BUDDY_ONLY_SECONDS` requires `caller == buddy`.
- After that subwindow, slash is permissionless again.

Boundary conditions (important for tests):

- At `now == alarm_time`: `ack_awake/snooze` are allowed.
- At `now == deadline`:
  - `ack_awake/snooze` are not allowed.
  - `slash` is allowed only if status is `Created`.
  - `claim` is still allowed if status is `Acknowledged`.
- At `now == deadline + CLAIM_GRACE_SECONDS`:
  - `claim` is still allowed if status is `Acknowledged`.
  - `sweep_acknowledged` is not allowed yet.
- At `now == deadline + CLAIM_GRACE_SECONDS + 1`:
  - `claim` is not allowed.
  - `sweep_acknowledged` is allowed.

## Transitions (Mermaid)

```mermaid
stateDiagram-v2
    [*] --> Created: create_alarm

    Created --> Acknowledged: ack_awake\n(now >= alarm_time && now < deadline)\n(owner)

    Created --> Created: snooze(expected_snooze_count)\n(now >= alarm_time && now < deadline)\n(owner, snooze_count < MAX)\n(expected == snooze_count)\n(+time, -deposit)

    Acknowledged --> Claimed: claim\n(now >= alarm_time && now <= deadline + CLAIM_GRACE_SECONDS)\n(owner, close vault -> owner)
    Acknowledged --> Claimed: sweep_acknowledged\n(now > deadline + CLAIM_GRACE_SECONDS)\n(any signer, close vault -> owner)

    Created --> Slashed: slash\n(now >= deadline)\n(Buddy route: buddy-only first window)\n(close vault -> penalty_recipient)

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
  - Requires `status == Acknowledged`.
  - Closes the vault to the owner (returns deposit + rent).
  - Sets status to `Claimed` and clears `remaining_amount`.
- `sweep_acknowledged`
  - Permissionless cleanup for acknowledged alarms after claim grace.
  - Closes the vault to the owner (returns deposit + rent), no penalty.
  - Sets status to `Claimed` and clears `remaining_amount`.
- `slash`
  - Requires `status == Created` (cannot slash acknowledged alarms).
  - Validates penalty recipient based on route.
  - Buddy route has a temporary buddy-only caller window after deadline.
  - Closes the vault to the penalty recipient (transfers deposit + rent).
  - Sets status to `Slashed` and clears `remaining_amount`.
- `emergency_refund`
  - Charges a percent penalty to `BURN_SINK` (rent-guarded).
  - Closes the vault to the owner.
  - Sets status to `Claimed` and clears `remaining_amount`.
