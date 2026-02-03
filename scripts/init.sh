#!/bin/bash
# init.sh - Initialize Solarma development environment
set -e

echo "🔧 Solarma Init Script"
echo "====================="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_command() {
    if command -v "$1" &> /dev/null; then
        echo -e "${GREEN}✓${NC} $1 found"
        return 0
    else
        echo -e "${RED}✗${NC} $1 not found"
        return 1
    fi
}

# Check Rust
echo ""
echo "Checking Rust toolchain..."
if check_command rustc; then
    rustc --version
fi
if check_command cargo; then
    cargo --version
fi

# Check Solana
echo ""
echo "Checking Solana CLI..."
if check_command solana; then
    solana --version
else
    echo -e "${YELLOW}→${NC} Install: sh -c \"\$(curl -sSfL https://release.solana.com/stable/install)\""
fi

# Check Anchor
echo ""
echo "Checking Anchor CLI..."
if check_command anchor; then
    anchor --version
else
    echo -e "${YELLOW}→${NC} Install: cargo install --git https://github.com/coral-xyz/anchor avm && avm install latest"
fi

# Check Node.js
echo ""
echo "Checking Node.js..."
if check_command node; then
    node --version
else
    echo -e "${YELLOW}→${NC} Install Node.js 18+"
fi

# Check Java
echo ""
echo "Checking Java..."
if check_command java; then
    java --version 2>&1 | head -1
else
    echo -e "${YELLOW}→${NC} Install JDK 17+"
fi

# Check Android SDK
echo ""
echo "Checking Android SDK..."
if [ -n "$ANDROID_HOME" ] || [ -n "$ANDROID_SDK_ROOT" ]; then
    echo -e "${GREEN}✓${NC} ANDROID_HOME or ANDROID_SDK_ROOT is set"
else
    echo -e "${YELLOW}→${NC} Set ANDROID_HOME or install Android Studio"
fi

# Initialize Rust toolchain if rust-toolchain.toml exists
if [ -f "rust-toolchain.toml" ]; then
    echo ""
    echo "Installing Rust toolchain from rust-toolchain.toml..."
    rustup show
fi

# Install npm dependencies for Anchor tests
if [ -f "programs/solarma_vault/package.json" ]; then
    echo ""
    echo "Installing npm dependencies..."
    cd programs/solarma_vault && npm install && cd ../..
fi

echo ""
echo "====================="
echo -e "${GREEN}Init check complete!${NC}"
