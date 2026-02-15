# Solarma Makefile
# Unified entry point for all build operations

.PHONY: init format lint typecheck test build run clean audit help
.PHONY: lint-strict test-strict format-strict

# Default target
help:
	@echo "Solarma Build Commands:"
	@echo ""
	@echo "  make init          - Install/verify toolchain"
	@echo "  make format        - Format all code (best-effort)"
	@echo "  make format-strict - Format all code (fail on missing tools)"
	@echo "  make lint          - Run linters (best-effort)"
	@echo "  make lint-strict   - Run linters (fail on error)"
	@echo "  make typecheck     - Run static analysis"
	@echo "  make test          - Run all tests (best-effort)"
	@echo "  make test-strict   - Run all tests (fail on error)"
	@echo "  make build         - Build all artifacts"
	@echo "  make run           - Start dev environment"
	@echo "  make clean         - Safe cleanup"
	@echo "  make audit         - Run security checks"
	@echo "  make pre-commit    - Install pre-commit hooks"
	@echo ""

# Initialize toolchain and environment
init:
	@echo "🔧 Checking toolchain..."
	@./scripts/init.sh
	@echo "✅ Init complete"

# ── Format ────────────────────────────────────────────────

format-strict:
	@echo "📝 Formatting code (strict)..."
	cd programs/solarma_vault && cargo fmt
	cd apps/android && ./gradlew ktlintFormat
	@echo "✅ Format complete"

format:
	@echo "📝 Formatting code..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo fmt; \
	fi
	@if [ -d "apps/android" ]; then \
		if [ -n "$$ANDROID_HOME" ] || [ -n "$$ANDROID_SDK_ROOT" ]; then \
			cd apps/android && ./gradlew ktlintFormat 2>/dev/null || true; \
		fi; \
	fi
	@echo "✅ Format complete"

# ── Lint ──────────────────────────────────────────────────

lint-strict:
	@echo "🔍 Running linters (strict)..."
	cd programs/solarma_vault && cargo clippy --all-targets --all-features -- -D warnings
	cd apps/android && ./gradlew ktlintCheck lint
	@echo "✅ Lint complete"

lint:
	@echo "🔍 Running linters..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo clippy --all-targets --all-features -- -D warnings; \
	fi
	@if [ -d "apps/android" ]; then \
		if [ -n "$$ANDROID_HOME" ] || [ -n "$$ANDROID_SDK_ROOT" ]; then \
			cd apps/android && ./gradlew ktlintCheck lint 2>/dev/null || true; \
		fi; \
	fi
	@echo "✅ Lint complete"

# ── Typecheck ─────────────────────────────────────────────

typecheck:
	@echo "🔬 Type checking..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo check --all-targets; \
	fi
	@echo "✅ Typecheck complete"

# ── Test ──────────────────────────────────────────────────

test-strict:
	@echo "🧪 Running tests (strict)..."
	cd programs/solarma_vault && cargo test
	cd apps/android && ./gradlew testDebugUnitTest
	@echo "✅ Tests complete"

test:
	@echo "🧪 Running tests..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo test; \
	fi
	@if [ -f "programs/solarma_vault/Anchor.toml" ]; then \
		if command -v solana-test-validator >/dev/null 2>&1; then \
			cd programs/solarma_vault && anchor test 2>/dev/null || true; \
		fi; \
	fi
	@if [ -d "apps/android" ]; then \
		if [ -n "$$ANDROID_HOME" ] || [ -n "$$ANDROID_SDK_ROOT" ]; then \
			cd apps/android && ./gradlew testDebugUnitTest 2>/dev/null || true; \
		fi; \
	fi
	@echo "✅ Tests complete"

# ── Build ─────────────────────────────────────────────────

build:
	@echo "🏗️ Building..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && anchor build 2>/dev/null || cargo build --release; \
	fi
	@if [ -d "apps/android" ]; then \
		if [ -n "$$ANDROID_HOME" ] || [ -n "$$ANDROID_SDK_ROOT" ]; then \
			cd apps/android && ./gradlew assembleDebug 2>/dev/null || true; \
		fi; \
	fi
	@echo "✅ Build complete"

# ── Dev ───────────────────────────────────────────────────

run:
	@echo "🚀 Starting dev environment..."
	@echo "Android: cd apps/android && ./gradlew installDebug"
	@echo "Anchor: cd programs/solarma_vault && anchor localnet"

# ── Cleanup ───────────────────────────────────────────────

clean:
	@echo "🧹 Cleaning..."
	@./scripts/safe_run.sh clean
	@echo "✅ Clean complete"

# ── Security ──────────────────────────────────────────────

audit:
	@echo "🔐 Running security checks..."
	@./scripts/security-audit.sh

# ── Pre-commit ────────────────────────────────────────────

pre-commit:
	@echo "🪝 Installing pre-commit hooks..."
	@if command -v pre-commit >/dev/null 2>&1; then \
		pre-commit install --hook-type pre-commit --hook-type commit-msg; \
		echo "✅ Hooks installed"; \
	else \
		echo "❌ pre-commit not found. Install: pip install pre-commit"; \
		exit 1; \
	fi
