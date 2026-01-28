# Solarma

Seeker-first Android alarm app with optional Solana onchain commitment vault.

## Quick Start

```bash
make init    # Setup toolchain
make build   # Build everything
make test    # Run tests
```

## Documentation

- [AGENTS.md](./AGENTS.md) — Agent entry point
- [Architecture](./docs/ARCHITECTURE.md) — System design
- [Runbook](./docs/RUNBOOK.md) — Operations guide
- [Config Reference](./docs/CONFIG_REFERENCE.md) — Configuration options

## Project Structure

```
solarma/
├── apps/android/           # Android app
├── programs/solarma_vault/ # Solana program
├── .ai/                    # Agent context
├── scripts/                # Build utilities
└── docs/                   # Documentation
```

## License

Private repository. See [INTERNAL_LICENSE.md](./INTERNAL_LICENSE.md).
