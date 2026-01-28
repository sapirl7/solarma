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
echo "🔨 Building program..."
cd "$(dirname "$0")/../programs/solarma_vault"
anchor build

# Deploy
echo "📤 Deploying to devnet..."
anchor deploy

# Get program ID
PROGRAM_ID=$(solana-keygen pubkey target/deploy/solarma_vault-keypair.json)
echo ""
echo "✅ Deployed successfully!"
echo "Program ID: $PROGRAM_ID"
echo ""

# Update lib.rs with actual program ID
echo "📝 Updating program ID in lib.rs..."
sed -i '' "s/declare_id!(\".*\")/declare_id!(\"$PROGRAM_ID\")/" src/lib.rs

# Also update Android code
ANDROID_BUILDER="../../apps/android/app/src/main/java/app/solarma/wallet/SolarmaInstructionBuilder.kt"
if [ -f "$ANDROID_BUILDER" ]; then
    echo "📝 Updating program ID in Android..."
    sed -i '' "s/val PROGRAM_ID = PublicKey(\".*\")/val PROGRAM_ID = PublicKey(\"$PROGRAM_ID\")/" "$ANDROID_BUILDER"
fi

echo ""
echo "🎉 Deployment complete!"
echo ""
echo "View on Solana Explorer:"
echo "https://explorer.solana.com/address/$PROGRAM_ID?cluster=devnet"
