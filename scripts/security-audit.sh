#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT_DIR"

echo "🔐 Running security checks..."

if ! command -v cargo-audit &> /dev/null; then
  echo "cargo-audit not found. Install with: cargo install cargo-audit"
  exit 1
fi

if ! command -v cargo-deny &> /dev/null; then
  echo "cargo-deny not found. Install with: cargo install cargo-deny"
  exit 1
fi

if [ -f "programs/Cargo.toml" ]; then
  echo "→ cargo audit"
  cargo audit --manifest-path programs/Cargo.toml

  echo "→ cargo deny"
  cargo deny --manifest-path programs/Cargo.toml --config programs/solarma_vault/deny.toml check
fi

if [ -f "programs/solarma_vault/package.json" ]; then
  echo "→ npm audit"
  (cd programs/solarma_vault && npm audit --audit-level=high)
fi

echo "✅ Security checks complete"
