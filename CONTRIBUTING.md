# Contributing to Solarma

Thank you for your interest in contributing! Solarma is a community-driven project for the Solana Seeker ecosystem.

## Getting Started

### Prerequisites
- **Android**: Android Studio Hedgehog (2023.1.1)+ with Kotlin 1.9+
- **Rust**: rustup with stable toolchain
- **Solana**: Solana CLI 1.18+ and Anchor CLI 0.32+
- **Node**: Node.js 18+ with Yarn

### Setup Development Environment

```bash
# Clone the repo
git clone https://github.com/sapirl7/solarma.git
cd solarma

# Install Anchor dependencies  
cd programs/solarma_vault
yarn install

# Build smart contract
anchor build

# Run tests
anchor test

# For Android, open apps/android in Android Studio
```

## Development Workflow

### Branch Naming
- `feature/<description>` — New features
- `fix/<description>` — Bug fixes
- `chore/<description>` — Maintenance tasks

### Commit Messages
Follow [Conventional Commits](https://conventionalcommits.org/):
```
feat: add streak rewards to home screen
fix: resolve claim timing edge case
chore: update Anchor to 0.32.1
test: add TransactionQueue unit tests
```

### Pull Request Process
1. Fork and create feature branch
2. Write tests for new functionality
3. Ensure all tests pass locally
4. Update documentation if needed
5. Open PR with clear description
6. Wait for review

## Code Style

### Kotlin (Android)
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Compose for UI
- Add KDoc for public APIs

### Rust (Anchor)
- Format with `cargo fmt`
- Lint with `cargo clippy`
- Document public functions

### TypeScript (Tests)
- Use ESLint configuration
- Add type annotations

## Running Tests

### Android Unit Tests
```bash
cd apps/android
./gradlew testDebugUnitTest
```

### Anchor Integration Tests
```bash
cd programs/solarma_vault
anchor test
```

### Full Test Suite
```bash
make test
```

## Architecture Guidelines

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for:
- Component responsibilities
- Data flow patterns
- State management

## Questions?

- Open an issue for bugs or features
- See [discussions](https://github.com/sapirl7/solarma/discussions) for questions
- Join our Discord (coming soon)

## Code of Conduct

Be respectful and inclusive. See `CODE_OF_CONDUCT.md` (Contributor Covenant).
