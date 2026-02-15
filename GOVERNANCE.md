# Governance

## Branch Protection Policy

The following rules are enforced on the `main` branch via GitHub settings:

### Required Checks

| Check | Enforced | Description |
|---|---|---|
| `security` | ✅ | Gitleaks secret scanning |
| `rust-checks` | ✅ | `cargo fmt --check`, `clippy -D warnings`, `cargo test` |
| `anchor` | ✅ | `anchor build` + localnet integration tests |
| `android` | ✅ | `ktlintCheck`, Android Lint, unit tests, debug APK build |
| `docs` | ✅ | markdownlint on all `.md` files |

### Branch Rules

| Rule | Status |
|---|---|
| Require pull request before merging | ✅ |
| Require status checks to pass before merging | ✅ |
| Require branches to be up to date before merging | ✅ |
| Require conversation resolution before merging | ✅ |
| Do not allow bypassing the above settings | ✅ |
| Restrict force pushes to `main` | ✅ |
| Restrict deletions of `main` | ✅ |

### Merge Strategy

- **Squash merge** is the default for feature branches.
- **Merge commits** are used for release-please PRs.
- Dependabot patch/minor PRs are auto-merged after CI passes.

## Commit Convention

All commits must follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```text
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

**Allowed types:** `feat`, `fix`, `chore`, `docs`, `style`, `refactor`, `test`, `build`, `ci`, `perf`, `revert`

**Scopes (optional):** `vault`, `android`, `docs`, `ci`, `deps`

This is enforced via pre-commit hooks (`conventional-pre-commit`).

## Code Ownership

See [CODEOWNERS](.github/CODEOWNERS) for automatic review assignment.

## Release Process

See [RELEASE_CHECKLIST](docs/RELEASE_CHECKLIST.md) and [RUNBOOK_RELEASE](docs/RUNBOOK_RELEASE.md).
