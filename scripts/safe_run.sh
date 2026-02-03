#!/bin/bash
# safe_run.sh - Safety wrapper for potentially dangerous commands
set -e

# Allowed directories for cleanup
SAFE_CLEAN_DIRS=(
    "./build"
    "./dist"
    "./tmp"
    "./target"
    "./apps/android/app/build"
    "./apps/android/.gradle"
    "./programs/solarma_vault/target"
    "./node_modules"
)

# Blocked commands/patterns
BLOCKED_PATTERNS=(
    "rm -rf /"
    "rm -rf ~"
    "rm -rf \$HOME"
    "format"
    "mkfs"
    "dd if="
)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

case "$1" in
    clean)
        echo "🧹 Safe clean starting..."
        for dir in "${SAFE_CLEAN_DIRS[@]}"; do
            if [ -d "$dir" ]; then
                echo "  Cleaning $dir"
                rm -rf "$dir"
            fi
        done
        echo -e "${GREEN}✓${NC} Safe clean complete"
        ;;
    
    check)
        # Check if a command is safe to run
        CMD="$2"
        for pattern in "${BLOCKED_PATTERNS[@]}"; do
            if [[ "$CMD" == *"$pattern"* ]]; then
                echo -e "${RED}BLOCKED:${NC} Command contains dangerous pattern: $pattern"
                exit 1
            fi
        done
        echo -e "${GREEN}OK:${NC} Command appears safe"
        ;;
    
    *)
        echo "Usage: safe_run.sh <command>"
        echo ""
        echo "Commands:"
        echo "  clean  - Remove only safe directories"
        echo "  check  - Verify if a command is safe"
        ;;
esac
