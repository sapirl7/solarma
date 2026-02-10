# Upgrade Policy (On-Chain Program)

This document defines how Solarma manages upgrade authority for the on-chain
program and how we move from development deployments to a mainnet posture.

## Scope

- Program: `programs/solarma_vault`
- Networks: localnet, devnet, mainnet-beta

## Definitions

- **Program ID**: address of the deployed program.
- **Upgrade authority**: the key that can deploy an upgraded program binary.
- **Immutable program**: a program with upgrade authority removed (no upgrades).

## Current State (Fill In)

Record the current upgrade authority on each cluster.

- Devnet:
  - Program ID: `F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP`
  - Upgrade authority: `TBD`
- Mainnet-beta:
  - Program ID: `TBD`
  - Upgrade authority: `TBD`

How to verify:

```bash
solana program show <PROGRAM_ID> --output json
```

## Policy By Environment

### Localnet / Developer Machines

- Upgrade authority may be a single developer keypair.
- Keys must never be committed to git.

### Devnet

- Upgrade authority may be a single key during rapid iteration, but:
  - Key must be stored in a hardware wallet (preferred) or encrypted at rest.
  - No CI/CD job may hold the upgrade authority secret.
  - Upgrades must be intentional and traceable (tagged commits + changelog).

### Mainnet-Beta (Target Posture)

Before first mainnet release, we must choose one of:

1. Multisig upgrade authority (recommended).
2. Timelock-controlled upgrade authority.
3. Single key in hardware wallet with strict operational controls (acceptable as
   an interim step, but higher risk).

After the system is mature, consider making the program immutable (only after
we have strong confidence in correctness and migration strategy).

## Upgrade Procedure (High Level)

1. Ensure all checks pass:
   - `make test`
   - `make lint`
   - `make audit`
2. Build program:
   - `cd programs/solarma_vault && anchor build`
3. Deploy upgrade on target cluster using a machine that has the upgrade
   authority available locally (never CI).
4. Verify:
   - Program ID unchanged (upgrade, not redeploy).
   - Upgrade authority unchanged (unless intentionally rotated).
   - Smoke tests on the target cluster.

## Rotating Upgrade Authority

Rotation is a security event. Record:

- old authority pubkey
- new authority pubkey
- date/time
- git tag / commit
- reason for rotation

Typical command (verify flags for your Solana CLI version):

```bash
solana program set-upgrade-authority <PROGRAM_ID> \
  --new-upgrade-authority <NEW_AUTHORITY_PUBKEY>
```

## Emergency Policy

If a vulnerability is discovered:

- Prefer a UI/feature-flag mitigation first (stop creating new vaults, stop
  submitting claim/slash) while we assess impact.
- If upgrading is required, follow the incident process in
  `docs/INCIDENT_RESPONSE.md`.

