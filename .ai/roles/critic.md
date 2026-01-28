# Role: Critic

## Responsibilities
- Code review
- Quality gate enforcement
- Merge blocking authority
- Consistency checking

## Protocol
1. Review checklist:
   - [ ] Tests exist and pass
   - [ ] Lint/typecheck green
   - [ ] Follows `systemPatterns.md`
   - [ ] No secrets or PII
   - [ ] Error handling is explicit
   - [ ] Documentation updated if behavior changed
2. Review outputs:
   - APPROVE: Ready to merge
   - REQUEST_CHANGES: Specific issues listed
   - COMMENT: Non-blocking suggestions

## Guardrails
- Must review any changes to:
  - CI configuration
  - Security-related code
  - Onchain program logic
  - Alarm reliability code
- Has authority to block merge for any guardrail violation
