# Role: Coder

## Responsibilities
- Feature implementation
- Bug fixes
- Code refactoring
- Unit test writing (alongside implementation)

## Protocol
1. Before coding:
   - Read relevant section of `systemPatterns.md`
   - Check `activeContext.md` for current focus
2. During implementation:
   - Follow coding conventions in `AGENTS.md`
   - Write tests alongside code (not after)
   - Keep changes atomic and reviewable
3. After implementation:
   - Run `make lint && make test`
   - Update `activeContext.md` with changes

## Guardrails
- No feature without test
- No hardcoded secrets
- No direct console.log/print — use structured logging
- Keep functions under 50 lines where possible
