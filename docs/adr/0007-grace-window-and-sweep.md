# ADR-0007: Claim Grace Window + Permissionless Sweep (ACKed Alarms)

**Status:** Accepted  
**Date:** 2026-02-10  
**Author:** sapirl7

## Context

The protocol uses hard clock windows. Even with `ack_awake` recorded on-chain,
an honest user can still lose funds at the deadline boundary due to:

- RPC latency / temporary endpoint failure.
- Blockhash expiry requiring rebuild + re-sign.
- Mobile background limitations causing delayed sends.

If the user ACKed before the deadline (proving they woke up), slashing them
because the follow-up `claim` landed slightly late is an undesirable UX loss.

We also want to avoid a liveness failure where an ACKed alarm can remain stuck
forever if the owner never submits a successful `claim`.

## Decision

1. Introduce a short **claim grace window** after `alarm.deadline`:
   - Constant: `CLAIM_GRACE_SECONDS` (default `120`).
   - `claim` is allowed only when `alarm.status == Acknowledged` and
     `now <= deadline + CLAIM_GRACE_SECONDS`.

2. Add a new **permissionless sweep** instruction:
   - `sweep_acknowledged` is allowed only when `alarm.status == Acknowledged`
     and `now > deadline + CLAIM_GRACE_SECONDS`.
   - It closes the vault and returns all remaining funds to `alarm.owner`.
   - No penalty is applied.

3. Buddy-route **buddy-only slash window** (quality-of-life):
   - Constant: `BUDDY_ONLY_SECONDS` (default `120`).
   - For Buddy route only, in `deadline <= now < deadline + BUDDY_ONLY_SECONDS`,
     `slash` requires `caller == alarm.penalty_destination`.
   - After that interval, `slash` becomes permissionless again.

## Rationale

- **Protect honest users.** If ACK landed in time, the protocol should treat the
  alarm as "success" even if the follow-up close happens slightly late.
- **Preserve credible slashing.** If the user did not ACK, slashing still opens
  exactly at `now >= deadline`.
- **No account layout change.** We avoid storing `ack_ts` by using status gating
  + deterministic clock windows derived from existing fields.
- **Liveness.** `sweep_acknowledged` guarantees funds can't get stuck forever in
  an ACKed state.

## Consequences

- Time windows no longer fully determine valid operations; `alarm.status` is now
  part of the authorization model (e.g., claim-after-deadline is possible only
  from `Acknowledged`).
- Clients must treat ACK as the required step for claim success. Old clients
  that attempt `claim` directly from `Created` will fail.

## Alternatives Considered

- Storing `ack_ts` inside `Alarm`: rejected (account layout change).
- No grace window: rejected (unnecessary honest-user losses).
