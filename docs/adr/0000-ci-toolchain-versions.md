# ADR-0000: Align CI Toolchain Versions

**Date**: 2026-02-03  
**Status**: Accepted

### Context

Toolchain versions diverged across docs, local scripts, Anchor.toml, and CI. This caused
non-reproducible builds and inconsistent developer environments.

### Decision

Align CI to the canonical toolchain defined in `docs/TOOLCHAIN.md` and
`programs/solarma_vault/Anchor.toml`:

- Anchor CLI 0.32.1
- Solana CLI 1.18.26

### Alternatives Considered

- Downgrade local docs/scripts to match CI (rejected: conflicts with Anchor.toml and dependencies)
- Keep versions drifting and document differences (rejected: harms reproducibility)

### Consequences

- CI builds match local and documented versions.
- Future toolchain changes must update `docs/TOOLCHAIN.md` and CI together.

### References

- `docs/TOOLCHAIN.md`
- `programs/solarma_vault/Anchor.toml`
