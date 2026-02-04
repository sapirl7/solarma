#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

echo "🔐 Running security checks..."

# Ensure cargo-audit can write its advisory DB in sandboxed environments
export CARGO_HOME="$ROOT_DIR/.cargo-home"
mkdir -p "$CARGO_HOME"

if ! command -v cargo-audit &> /dev/null; then
  echo "cargo-audit not found. Install with: cargo install cargo-audit"
  exit 1
fi

if ! command -v cargo-deny &> /dev/null; then
  echo "cargo-deny not found. Install with: cargo install cargo-deny"
  exit 1
fi

if [ -f "programs/Cargo.lock" ]; then
  echo "→ cargo audit"
  cargo audit --file programs/Cargo.lock --ignore RUSTSEC-2025-0141

  echo "→ cargo deny"
  cargo deny --manifest-path programs/Cargo.toml check --config programs/solarma_vault/deny.toml
fi

if [ -f "programs/solarma_vault/package.json" ]; then
  echo "→ npm audit"
  (cd programs/solarma_vault && npm audit --audit-level=high)
fi

echo "✅ Security checks complete"
