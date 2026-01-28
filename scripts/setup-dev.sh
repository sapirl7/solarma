#!/bin/bash
# Solarma Development Setup Script
# Run this to set up the development environment

set -e

echo "🌅 Solarma Development Setup"
echo "============================"

# Check for Rust
if ! command -v cargo &> /dev/null; then
    echo "📦 Installing Rust..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
fi

# Check for Solana CLI
if ! command -v solana &> /dev/null; then
    echo "📦 Installing Solana CLI..."
    sh -c "$(curl -sSfL https://release.solana.com/v1.18.4/install)"
    export PATH="$HOME/.local/share/solana/install/active_release/bin:$PATH"
fi

# Check for Anchor
if ! command -v anchor &> /dev/null; then
    echo "📦 Installing Anchor..."
    cargo install --git https://github.com/coral-xyz/anchor avm --locked --force
    avm install 0.29.0
    avm use 0.29.0
fi

# Check for Node.js
if ! command -v node &> /dev/null; then
    echo "⚠️  Node.js not found. Please install Node.js 18+ manually."
    exit 1
fi

# Install Node dependencies
echo "📦 Installing Node dependencies..."
npm install

# Set up Solana for devnet
echo "🔧 Configuring Solana for devnet..."
solana config set --url devnet

# Generate keypair if not exists
if [ ! -f ~/.config/solana/id.json ]; then
    echo "🔑 Generating Solana keypair..."
    solana-keygen new --no-bip39-passphrase
fi

# Airdrop some SOL for testing
echo "💰 Requesting devnet SOL airdrop..."
solana airdrop 2 || echo "Airdrop failed (rate limited), try again later"

# Build Anchor program
echo "🔨 Building Anchor program..."
cd programs/solarma_vault
anchor build

echo ""
echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Deploy to devnet:  anchor deploy"
echo "  2. Run tests:         anchor test"
echo "  3. Build Android:     cd apps/android && ./gradlew assembleDebug"
echo ""
echo "Your wallet address: $(solana address)"
echo "Your balance: $(solana balance)"
