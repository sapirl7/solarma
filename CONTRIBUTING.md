# Contributing to Solarma

## For AI Agents

1. **Always start with context**:
   - Read `AGENTS.md`
   - Check `.ai/memory-bank/activeContext.md`

2. **Follow the workflow**:
   - Update `activeContext.md` before starting work
   - Run `make lint && make test` after changes
   - Update `progress.md` after completion
   - Add ADR if making architectural decisions

3. **Respect guardrails**:
   - No secrets in code
   - No changes to protected files without ADR
   - No merging without tests

## For Human Contributors

1. **Setup**:
   ```bash
   git clone git@github.com:sapirl7/solarma.git
   cd solarma
   make init
   ```

2. **Branching**:
   - `main` — stable, protected
   - `dev` — integration branch
   - `feature/*` — feature branches

3. **Commit Messages**:
   ```
   type(scope): description

   [optional body]
   ```
   Types: feat, fix, docs, style, refactor, test, chore

4. **Pull Requests**:
   - Must pass CI
   - Must have tests for new functionality
   - Must update docs if behavior changes

## Questions

Open an issue or reach out to the team.
