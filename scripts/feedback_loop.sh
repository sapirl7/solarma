#!/bin/bash
# feedback_loop.sh - Quality assessment script
set -e

echo "📊 Solarma Quality Report"
echo "========================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Results
TESTS_RESULT="SKIP"
LINT_RESULT="SKIP"
BUILD_RESULT="SKIP"
COVERAGE="N/A"

# Create tmp directory for reports
mkdir -p ./tmp/feedback

# Run tests
echo "🧪 Running tests..."
if make test 2>&1 | tee ./tmp/feedback/test.log | tail -5; then
    if grep -q "error\|FAILED\|failure" ./tmp/feedback/test.log 2>/dev/null; then
        TESTS_RESULT="FAIL"
    else
        TESTS_RESULT="PASS"
    fi
else
    TESTS_RESULT="FAIL"
fi

# Run lint
echo ""
echo "🔍 Running linters..."
if make lint 2>&1 | tee ./tmp/feedback/lint.log | tail -5; then
    if grep -q "error\|warning" ./tmp/feedback/lint.log 2>/dev/null; then
        LINT_RESULT="WARN"
    else
        LINT_RESULT="PASS"
    fi
else
    LINT_RESULT="FAIL"
fi

# Run build
echo ""
echo "🏗️ Running build..."
if make build 2>&1 | tee ./tmp/feedback/build.log | tail -5; then
    BUILD_RESULT="PASS"
else
    BUILD_RESULT="FAIL"
fi

# Color helper
result_color() {
    case "$1" in
        PASS) echo -e "${GREEN}$1${NC}" ;;
        FAIL) echo -e "${RED}$1${NC}" ;;
        WARN) echo -e "${YELLOW}$1${NC}" ;;
        *) echo "$1" ;;
    esac
}

# Summary
echo ""
echo "========================="
echo "📊 QUALITY SUMMARY"
echo "========================="
echo -e "tests=$(result_color $TESTS_RESULT) lint=$(result_color $LINT_RESULT) build=$(result_color $BUILD_RESULT) coverage=$COVERAGE"
echo ""

# One-liner for CI/agent parsing
echo "QUALITY: tests=$TESTS_RESULT lint=$LINT_RESULT build=$BUILD_RESULT coverage=$COVERAGE"

# Exit with error if any critical failure
if [ "$TESTS_RESULT" = "FAIL" ] || [ "$BUILD_RESULT" = "FAIL" ]; then
    exit 1
fi
