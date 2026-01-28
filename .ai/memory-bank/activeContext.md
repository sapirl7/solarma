# Solarma — Active Context

## Current Focus
Phase 2 Vault Contract MVP complete. Ready for commit and Phase 3 planning.

## Recently Completed (2026-01-28)
- Full deposit/claim/snooze/slash logic with SOL transfers
- Exponential snooze penalty curve (10% * 2^count)
- Penalty routes: Burn/Donate/Buddy
- Unit tests (Rust) and integration tests (TypeScript)
- 12 error codes for comprehensive validation

## Files Modified in Phase 2
- `src/lib.rs` - Updated signatures
- `src/constants.rs` - NEW: burn sink, snooze limits
- `src/error.rs` - Extended to 12 errors
- `src/state.rs` - Added vault_bump field
- `src/instructions/create_alarm.rs` - Full deposit handling
- `src/instructions/claim.rs` - Time check + transfer
- `src/instructions/snooze.rs` - Exponential curve
- `src/instructions/slash.rs` - Route execution
- `src/tests.rs` - NEW: unit tests
- `tests/solarma_vault.ts` - NEW: TypeScript tests

## Next Steps
1. Commit and push Phase 2
2. Plan Phase 3: Android Alarm Engine
