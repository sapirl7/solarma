# Security Checks

This project includes lightweight security checks for dependencies and advisories.

## Prerequisites
- `cargo-audit`: `cargo install cargo-audit`
- `cargo-deny`: `cargo install cargo-deny`

## Run
```bash
make audit
```

## What It Does
- Rust advisories via `cargo audit`
- License and advisory checks via `cargo deny`
- Node dependency audit via `npm audit --audit-level=high`

## Configuration
- Rust policy: `programs/solarma_vault/deny.toml`
- Node dependencies: `programs/solarma_vault/package.json`
