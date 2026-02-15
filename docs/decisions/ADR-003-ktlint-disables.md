# ADR-003: KtLint Disabled Rules for Compose Conventions

**Status:** Accepted
**Date:** 2026-02-15
**Context:** PR #33 ktlint cleanup

## Decision

Three ktlint rules are globally disabled in `apps/android/.editorconfig`:

| Rule | Reason | Re-enable When |
|------|--------|----------------|
| `argument-list-wrapping` | Parser crash on Compose DSL lambdas passed as named parameters. Known upstream bug. | ktlint ≥ 1.6 ships parser fix. Track [ktlint#2828](https://github.com/pinterest/ktlint/issues/2828). |
| `function-naming` | Compose `@Composable` functions use PascalCase by convention. ktlint expects camelCase. | ktlint adds a `@Composable` annotation-aware exception, or project adopts a ktlint plugin that supports it. |
| `no-wildcard-imports` | Compose code uses `.*` imports for `androidx.compose.*` packages extensively. Expanding adds 20+ lines per file with no readability gain. | Project policy changes to require explicit imports; update all files accordingly. |

## Consequences

- **Main source** remains strict for all other ~60 ktlint rules.
- **Test source** inherits these disables but has no additional exceptions.
- These 3 rules align with official Compose coding guidelines and are disabled in most Compose projects.

## Review Schedule

Check feasibility of re-enabling these rules at each ktlint major version bump.
Current ktlint: 1.5.x. Next check: ktlint 1.6.0 release.
