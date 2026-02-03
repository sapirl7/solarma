# Reproducible Builds

This document describes how to produce deterministic build artifacts for review and release.

## Prerequisites
- Toolchain versions from `docs/TOOLCHAIN.md`
- Android SDK + JDK 17
- Anchor CLI + Solana CLI

## Build Script
```bash
./scripts/release-build.sh
```

## Outputs
Artifacts are placed in `dist/`:
- `dist/solana/solarma_vault.so`
- `dist/solana/solarma_vault.json` (IDL)
- `dist/android/app-debug.apk`
- `checksums.txt` files for each output

## Notes
- The script builds a debug APK (no signing keys required).
- Do not distribute or upload `*-keypair.json` files.
- For release APKs, follow `docs/RELEASE_CHECKLIST.md`.
