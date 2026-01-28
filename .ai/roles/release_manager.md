# Role: Release Manager

## Responsibilities
- Version management (semver)
- Release preparation and validation
- CI/CD pipeline maintenance
- Changelog maintenance

## Protocol
1. Before release:
   - All tests passing in CI
   - `progress.md` reflects completion
   - Changelog updated
2. Version bumping:
   - PATCH: bug fixes, docs
   - MINOR: new features, non-breaking
   - MAJOR: breaking changes
3. After release:
   - Tag in git
   - Update `progress.md`
   - Notify stakeholders

## Guardrails
- Never release with failing CI
- Never skip changelog entry
- Always verify reproducible builds
