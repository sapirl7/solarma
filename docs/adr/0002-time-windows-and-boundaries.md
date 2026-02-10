# ADR-0002: Time Windows and Boundary Conditions

**Status:** Accepted  
**Date:** 2026-01-28  
**Author:** sapirl7

## Context

The alarm lifecycle divides time into three non-overlapping windows. The
boundary conditions (exactly at `alarm_time` and `deadline`) must be
unambiguous to prevent exploits where both claim and slash are valid.

## Decision

Three windows with these exact boundaries:

| Window | Condition | Instructions allowed |
|--------|-----------|---------------------|
| Refund | `now < alarm_time` | `emergency_refund` |
| Active | `now >= alarm_time && now < deadline` | `claim`, `ack_awake`, `snooze` |
| Slash  | `now >= deadline` | `slash` |

Key boundary rules:

- At `now == alarm_time`: **Active window opens.** Claim/ack/snooze are valid.
  Refund is NOT valid (alarm has fired).
- At `now == deadline`: **Slash window opens.** Claim is NOT valid.
  This is the strictest possible interpretation — the owner had their chance.

## Rationale

- **No overlap.** At any given `now`, exactly one of {refund, claim, slash}
  is valid. This is a partition of the time axis, verified by
  `fuzz_windows_partition_time_axis` across 10,000 random scenarios.
- **Strict deadline = credible commitment.** If claim were valid at
  `now == deadline`, there's a race between claimer and slasher in the same
  slot. The owner would win (they sign first), weakening the penalty threat.
- **Simple mental model.** "Before alarm = can cancel. Between alarm and
  deadline = can claim. After deadline = slashed." No edge cases for users.

## Consequences

- Clock manipulation (validator time skew) is a known Solana risk. On devnet
  this is acceptable. For mainnet, consider documenting this in SECURITY.md.
- The snooze instruction extends BOTH `alarm_time` and `deadline` by the
  same amount, preserving the window width.

## Alternatives Considered

- **Inclusive deadline for claim** (`now <= deadline`): rejected due to the
  race condition between claim and slash at `now == deadline`.
- **Grace period after deadline**: rejected as over-engineering — the snooze
  mechanism already provides the "extra time" escape valve.
