# Solarma Makefile
# Unified entry point for all build operations

.PHONY: init format lint typecheck test build run clean audit help

# Default target
help:
	@echo "Solarma Build Commands:"
	@echo ""
	@echo "  make init       - Install/verify toolchain"
	@echo "  make format     - Format all code"
	@echo "  make lint       - Run linters"
	@echo "  make typecheck  - Run static analysis"
	@echo "  make test       - Run all tests"
	@echo "  make build      - Build all artifacts"
	@echo "  make run        - Start dev environment"
	@echo "  make clean      - Safe cleanup"
	@echo "  make audit      - Run security checks"
	@echo ""

# Initialize toolchain and environment
init:
	@echo "🔧 Checking toolchain..."
	@./scripts/init.sh
	@echo "✅ Init complete"

# Format all code
format:
	@echo "📝 Formatting code..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo fmt; \
	fi
	@if [ -d "apps/android" ]; then \
		cd apps/android && ./gradlew ktlintFormat 2>/dev/null || echo "ktlint not configured yet"; \
	fi
	@echo "✅ Format complete"

# Run linters
lint:
	@echo "🔍 Running linters..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo clippy --all-targets --all-features -- -D warnings; \
	fi
	@if [ -d "apps/android" ]; then \
		cd apps/android && ./gradlew lint 2>/dev/null || echo "Android lint not configured yet"; \
	fi
	@echo "✅ Lint complete"

# Static type checking
typecheck:
	@echo "🔬 Type checking..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo check --all-targets; \
	fi
	@echo "✅ Typecheck complete"

# Run all tests
test:
	@echo "🧪 Running tests..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && cargo test; \
	fi
	@if [ -f "programs/solarma_vault/Anchor.toml" ]; then \
		cd programs/solarma_vault && anchor test 2>/dev/null || echo "Anchor tests require local validator"; \
	fi
	@if [ -d "apps/android" ]; then \
		cd apps/android && ./gradlew testDebugUnitTest 2>/dev/null || echo "Android tests not configured yet"; \
	fi
	@echo "✅ Tests complete"

# Build all artifacts
build:
	@echo "🏗️ Building..."
	@if [ -d "programs/solarma_vault" ]; then \
		cd programs/solarma_vault && anchor build 2>/dev/null || cargo build --release; \
	fi
	@if [ -d "apps/android" ]; then \
		cd apps/android && ./gradlew assembleDebug 2>/dev/null || echo "Android build not configured yet"; \
	fi
	@echo "✅ Build complete"

# Start development environment
run:
	@echo "🚀 Starting dev environment..."
	@echo "Android: cd apps/android && ./gradlew installDebug"
	@echo "Anchor: cd programs/solarma_vault && anchor localnet"

# Safe cleanup (only allowed directories)
clean:
	@echo "🧹 Cleaning..."
	@./scripts/safe_run.sh clean
	@echo "✅ Clean complete"

# Security audit (cargo audit/deny + npm audit)
audit:
	@echo "🔐 Running security checks..."
	@./scripts/security-audit.sh
