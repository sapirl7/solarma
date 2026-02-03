#!/bin/bash
# Deploy Solarma to Solana devnet
set -e

echo "🚀 Deploying Solarma to Devnet"
echo "=============================="

# Ensure we're on devnet
solana config set --url devnet

# Check balance
BALANCE=$(solana balance | cut -d' ' -f1)
echo "Current balance: $BALANCE SOL"

if (( $(echo "$BALANCE < 1" | bc -l) )); then
    echo "⚠️  Low balance, requesting airdrop..."
    solana airdrop 2 || echo "Airdrop failed, continuing anyway"
fi

# Build
cd "$(dirname "$0")/../programs/solarma_vault"

# Ensure deploy keypair exists and declared program ID matches BEFORE build/deploy
KEYPAIR_PATH="target/deploy/solarma_vault-keypair.json"
WORKSPACE_KEYPAIR="../target/deploy/solarma_vault-keypair.json"
if [ ! -f "$KEYPAIR_PATH" ] && [ ! -f "$WORKSPACE_KEYPAIR" ]; then
  echo "ℹ️  Keypair not found, running initial build to generate it..."
  anchor build
fi
if [ ! -f "$KEYPAIR_PATH" ] && [ -f "$WORKSPACE_KEYPAIR" ]; then
  KEYPAIR_PATH="$WORKSPACE_KEYPAIR"
fi

PROGRAM_ID=$(solana-keygen pubkey "$KEYPAIR_PATH")
echo "📝 Using Program ID: $PROGRAM_ID"

echo "📝 Updating program ID in lib.rs and Anchor.toml..."
sed -i '' "s/declare_id!(\".*\")/declare_id!(\"$PROGRAM_ID\")/" src/lib.rs
sed -i '' "s/solarma_vault = \".*\"/solarma_vault = \"$PROGRAM_ID\"/g" Anchor.toml

ANDROID_BUILDER="../../apps/android/app/src/main/java/app/solarma/wallet/SolarmaInstructionBuilder.kt"
if [ -f "$ANDROID_BUILDER" ]; then
  echo "📝 Updating program ID in Android..."
  sed -i '' "s/val PROGRAM_ID = PublicKey(\".*\")/val PROGRAM_ID = PublicKey(\"$PROGRAM_ID\")/" "$ANDROID_BUILDER"
fi

echo "🔨 Building program..."
anchor build

# Anchor may output to workspace target/ when a cargo workspace is used.
WORKSPACE_SO="../target/deploy/solarma_vault.so"
LOCAL_SO="target/deploy/solarma_vault.so"
if [ -f "$WORKSPACE_SO" ]; then
  echo "ℹ️  Syncing program binary from workspace target to local target..."
  mkdir -p "$(dirname "$LOCAL_SO")"
  cp "$WORKSPACE_SO" "$LOCAL_SO"
fi

# Deploy
echo "📤 Deploying to devnet..."
anchor deploy

echo ""
echo "✅ Deployed successfully!"
echo "Program ID: $PROGRAM_ID"
echo ""

echo ""
echo "🎉 Deployment complete!"
echo ""
echo "View on Solana Explorer:"
echo "https://explorer.solana.com/address/$PROGRAM_ID?cluster=devnet"
