# Solarma Vault — Invariants

This document specifies *system-level invariants* for the on-chain vault
(`programs/solarma_vault`). Each invariant should be testable (unit, e2e, or
property-based) and treated as part of the contract.

## State Machine

- I01: Terminal states are absorbing: once `AlarmStatus` is `Claimed` or
  `Slashed`, no instruction can transition it to any other state.
- I02: The only valid status transitions are:
  `Created -> Acknowledged`, `Created -> Claimed`, `Created -> Slashed`,
  `Acknowledged -> Claimed`.
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
- I07: `ack_awake` is only valid after the alarm fires and strictly before the
  deadline: `now >= alarm.alarm_time && now < alarm.deadline`.
- I08: `snooze` is only valid in the same window as `ack_awake`:
  `now >= alarm.alarm_time && now < alarm.deadline`.
- I09: For an alarm in status `Acknowledged`, `claim` is valid up to (and
  including) `deadline + CLAIM_GRACE_SECONDS`.
- I10: For an alarm in status `Acknowledged`, `sweep_acknowledged` is valid
  strictly after `deadline + CLAIM_GRACE_SECONDS`.
- I11: For an alarm in status `Created`, `slash` is only valid at/after the
  deadline: `now >= alarm.deadline`.
- I12: `alarm.deadline` is strictly greater than `alarm.alarm_time` for the
  entire lifetime of the alarm.

## Authority / Access Control

- I13: Only `alarm.owner` can call `claim`.
- I14: Only `alarm.owner` can call `snooze`.
- I15: Only `alarm.owner` can call `ack_awake`.
- I16: Only `alarm.owner` can call `emergency_refund`.
- I17: `sweep_acknowledged` is permissionless: any signer may call it once the
  sweep window opens.
- I18: `slash` is permissionless once the buddy-only window (if applicable)
  ends.

## Funds Safety (Rent + Accounting)

- I19: The vault account must never drop below its rent-exempt minimum due to
  partial deductions; the only time it may go below is during account close.
- I20: `snooze` deduction is capped at the available balance above rent-exempt
  minimum.
- I21: `emergency_refund` penalty is capped at the available balance above
  rent-exempt minimum.
- I22: `alarm.remaining_amount` is monotonically non-increasing and never
  underflows.
- I23: `alarm.remaining_amount <= alarm.initial_amount` always holds.
- I24: After any terminalizing instruction (`claim`, `sweep_acknowledged`,
  `slash`, `emergency_refund`)
  `alarm.remaining_amount == 0`.

## Identity / PDA Invariants

- I25: Alarm PDA address is uniquely determined by `(owner, alarm_id)`.
- I26: Vault PDA address is uniquely determined by `alarm` pubkey.

## Recipient / Routing Invariants

- I27: For `slash`, the `penalty_recipient` must match the route:
  Burn -> `BURN_SINK`; Donate/Buddy -> `alarm.penalty_destination`.
- I28: For `snooze` and `emergency_refund`, the sink must be `BURN_SINK`.

## Monotonicity / Counters

- I29: `alarm.snooze_count` increases by exactly 1 on every successful `snooze`.
- I30: `alarm.snooze_count` never exceeds `MAX_SNOOZE_COUNT`.
- I31: Each successful `snooze` increases both `alarm.alarm_time` and
  `alarm.deadline` by exactly `DEFAULT_SNOOZE_EXTENSION_SECONDS`.

## Protocol Hardening (ACK / Grace / Sweep)

- I32: If `alarm.status == Acknowledged`, then `slash` must be invalid at all
  times (ACK makes slashing impossible).
- I33: For Buddy route, in the interval
  `deadline <= now < deadline + BUDDY_ONLY_SECONDS`, only the buddy
  (`alarm.penalty_destination`) may successfully call `slash`.
- I34: For `alarm.status == Acknowledged`, `claim` and `sweep_acknowledged`
  windows must not overlap:
  - At `now == deadline + CLAIM_GRACE_SECONDS`, claim is valid and sweep is invalid.
  - At `now == deadline + CLAIM_GRACE_SECONDS + 1`, claim is invalid and sweep is valid.
