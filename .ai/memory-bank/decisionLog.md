# Solarma — Decision Log

Architecture Decision Records (ADR) for significant choices.

---

## ADR-001: Monorepo Structure

**Date**: 2026-01-28  
**Status**: Accepted

### Context
Need to manage Android app + Anchor program + shared tooling in one repository.

### Decision
Use monorepo with clear directory separation:
- `apps/android/` — Android application
- `programs/solarma_vault/` — Anchor program
- `packages/shared/` — Minimal shared utilities
- `scripts/` — Build/CI utilities

### Consequences
- Single source of truth
- Unified versioning possible
- CI must handle multiple languages (Kotlin, Rust, TypeScript)

---

## ADR-002: Wake Proof On-Device Only

**Date**: 2026-01-28  
**Status**: Accepted

### Context
Could verify wake proof on-chain or via server attestation.

### Decision
All Wake Proof verification runs locally on device. Contract only verifies time/status for claim.

### Consequences
- Simpler contract logic
- User must trust app (acceptable for MVP)
- Future: could add optional attestation layer
- Privacy: no sensor data leaves device

---

## ADR-003: Agent-First Repository

**Date**: 2026-01-28  
**Status**: Accepted

### Context
Repository designed for AI agent development (Google Antigravity).

### Decision
- All agent context in `.ai/` directory
- Memory Bank pattern for persistent knowledge
- Role definitions for multi-agent workflows
- AGENTS.md as universal entry point

### Consequences
- Agents can self-orient via standardized files
- Human reviewers can audit agent decisions via memory files
- Requires discipline to keep memory updated
