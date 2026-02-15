# ADR-0006: Claim Grace Window, Sweep, and Buddy-Only Slash Window

## Status

Accepted

## Context

Two production risks were identified around deadline boundaries:

1. Honest users can ACK in time but lose funds if CLAIM confirmation misses the exact deadline.
2. Buddy route can be front-run by third parties immediately at deadline, reducing accountability UX.

At the same time, account layout migration should be avoided for a young protocol with active devnet usage.

## Decision

We introduce three protocol rules without changing `Alarm` account layout:

1. `claim` requires `status == Acknowledged` and is valid until:
   `now <= deadline + CLAIM_GRACE_SECONDS` (default 120s).
2. New permissionless instruction `sweep_acknowledged`:
   valid when `status == Acknowledged && now > deadline + CLAIM_GRACE_SECONDS`,
   closes vault to owner with no penalty.
3. `slash` is valid only from `status == Created`.
   For `PenaltyRoute::Buddy` only:
   `deadline <= now < deadline + BUDDY_ONLY_SECONDS` (default 120s) requires
   `caller == buddy`. After that window, slash is permissionless again.

## Consequences

### Positive

- Reduces honest-user losses from confirmation latency at deadline.
- Keeps slash permissionless model while giving buddy a short first-right window.
- No account migration needed.

### Negative / Tradeoffs

- Protocol timing complexity increases (grace + sweep + buddy-only subwindow).
- Additional instruction surface (`sweep_acknowledged`) must be tested and monitored.
- Clients and docs must stay in sync with updated windows.

## Invariants Introduced

- ACK implies slash is impossible (status-gated).
- CLAIM and SWEEP windows are disjoint at `deadline + CLAIM_GRACE_SECONDS`.
- Sweep is terminal and returns remaining funds to owner (no penalty route usage).

