# Solarma Vault — Invariants

This document specifies *system-level invariants* for the on-chain vault
(`programs/solarma_vault`). Each invariant should be testable (unit, e2e, or
property-based) and treated as part of the contract.

## State Machine

- I01: Terminal states are absorbing: once `AlarmStatus` is `Claimed` or
  `Slashed`, no instruction can transition it to any other state.
- I02: The only valid status transitions are:
  `Created -> Acknowledged`, `Created -> Slashed`,
  `Acknowledged -> Claimed` (via `claim` or `sweep_acknowledged`).
- I03: `snooze` does not change `AlarmStatus` (it is a self-transition on
  `Created`).
- I04: `ack_awake` is single-shot: it can only be executed while status is
  `Created`.
- I05: `claim`, `sweep_acknowledged`, `slash`, `emergency_refund` are
  terminalizing: after success, the vault is closed and
  `alarm.remaining_amount == 0`.

## Time Windows (Clock)

- I06: `emergency_refund` is only valid before the alarm fires:
  `now < alarm.alarm_time`.
- I07: `claim` is only valid after the alarm fires and through grace:
  `now >= alarm.alarm_time && now <= alarm.deadline + CLAIM_GRACE_SECONDS`.
- I08: `ack_awake` is only valid in the active pre-deadline window:
  `now >= alarm.alarm_time && now < alarm.deadline`.
- I09: `snooze` is only valid in the active pre-deadline window:
  `now >= alarm.alarm_time && now < alarm.deadline`.
- I10: `slash` is only valid at/after the deadline: `now >= alarm.deadline`.
- I11: `sweep_acknowledged` is only valid after grace:
  `now > alarm.deadline + CLAIM_GRACE_SECONDS`.
- I12: `alarm.deadline` is strictly greater than `alarm.alarm_time` for the
  entire lifetime of the alarm.

## Authority / Access Control

- I13: Only `alarm.owner` can call `claim`.
- I14: Only `alarm.owner` can call `snooze`.
- I15: Only `alarm.owner` can call `ack_awake`.
- I16: Only `alarm.owner` can call `emergency_refund`.
- I17: `sweep_acknowledged` is permissionless: any signer may call it after
  claim grace has expired.

## Funds Safety (Rent + Accounting)

- I18: The vault account must never drop below its rent-exempt minimum due to
  partial deductions; the only time it may go below is during account close.
- I19: `snooze` deduction is capped at the available balance above rent-exempt
  minimum.
- I20: `emergency_refund` penalty is capped at the available balance above
  rent-exempt minimum.
- I21: `alarm.remaining_amount` is monotonically non-increasing and never
  underflows.
- I22: `alarm.remaining_amount <= alarm.initial_amount` always holds.
- I23: After any terminalizing instruction (`claim`, `slash`, `emergency_refund`)
  `alarm.remaining_amount == 0`.

## Identity / PDA Invariants

- I24: Alarm PDA address is uniquely determined by `(owner, alarm_id)`.
- I25: Vault PDA address is uniquely determined by `alarm` pubkey.

## Recipient / Routing Invariants

- I26: For `slash`, the `penalty_recipient` must match the route:
  Burn -> `BURN_SINK`; Donate/Buddy -> `alarm.penalty_destination`.
- I27: For `snooze` and `emergency_refund`, the sink must be `BURN_SINK`.

## Monotonicity / Counters

- I28: `alarm.snooze_count` increases by exactly 1 on every successful `snooze`.
- I29: `alarm.snooze_count` never exceeds `MAX_SNOOZE_COUNT`.
- I30: Each successful `snooze` increases both `alarm.alarm_time` and
  `alarm.deadline` by exactly `DEFAULT_SNOOZE_EXTENSION_SECONDS`.

## PR-1 Specific

- I31: `ack_awake` implies slash is impossible for that alarm because
  `slash` requires `status == Created`.
- I32: `claim` and `sweep_acknowledged` windows do not overlap:
  at `now == deadline + CLAIM_GRACE_SECONDS`, claim is still valid but sweep is
  invalid; at `now == deadline + CLAIM_GRACE_SECONDS + 1`, claim is invalid and
  sweep is valid.
- I33: For `PenaltyRoute::Buddy`, during
  `deadline <= now < deadline + BUDDY_ONLY_SECONDS`,
  `slash` requires `caller == buddy`.
