#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
OUT_DIR="$ROOT_DIR/dist"

checksum() {
  if command -v shasum &> /dev/null; then
    shasum -a 256 "$@"
  elif command -v sha256sum &> /dev/null; then
    sha256sum "$@"
  else
    echo "No checksum tool found (shasum/sha256sum)."
    return 0
  fi
}

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/android" "$OUT_DIR/solana"

echo "🔨 Building Anchor program..."
(cd "$ROOT_DIR/programs/solarma_vault" && anchor build)

cp "$ROOT_DIR/programs/solarma_vault/target/deploy/solarma_vault.so" "$OUT_DIR/solana/"
cp "$ROOT_DIR/programs/solarma_vault/target/idl/solarma_vault.json" "$OUT_DIR/solana/"

checksum "$OUT_DIR/solana/solarma_vault.so" "$OUT_DIR/solana/solarma_vault.json" > "$OUT_DIR/solana/checksums.txt" || true

echo "📦 Building Android debug APK..."
(cd "$ROOT_DIR/apps/android" && ./gradlew assembleDebug)

cp "$ROOT_DIR/apps/android/app/build/outputs/apk/debug/app-debug.apk" "$OUT_DIR/android/"
checksum "$OUT_DIR/android/app-debug.apk" > "$OUT_DIR/android/checksums.txt" || true

echo "✅ Release artifacts prepared in $OUT_DIR"
