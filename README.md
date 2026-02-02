<p align="center">
  <img src="docs/assets/solarma-logo.png" alt="Solarma Logo" width="120" />
</p>

<h1 align="center">Solarma</h1>

<p align="center">
  <strong>Wake-proof alarm with SOL commitment vault</strong><br>
  Built for <a href="https://solanamobile.com/seeker">Solana Seeker</a>
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#how-it-works">How It Works</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#contributing">Contributing</a> •
  <a href="#roadmap">Roadmap</a>
</p>

---

## Features

🌅 **Commitment Alarm** — Deposit SOL to back your wake-up commitment

📱 **Native Android** — Optimized for Solana Seeker hardware

🔐 **Non-custodial** — Your keys, your funds (MWA integration)

⏰ **Wake Proof** — Verify wakeup via NFC, QR code, or step counter

💸 **Penalty Routes** — Burn, Donate, or send to Buddy on failure

🔓 **Permissionless Slash** — Anyone can trigger after deadline

## How It Works

```
┌─────────────────────────────────────────────────────────┐
│                     SOLARMA FLOW                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐             │
│  │ CREATE  │───▶│  ALARM  │───▶│  CLAIM  │             │
│  │ + $SOL  │    │  TIME   │    │ ✓ PASS  │             │
│  └─────────┘    └────┬────┘    └─────────┘             │
│                      │                                  │
│                      ▼                                  │
│                ┌─────────┐    ┌─────────┐              │
│                │ SNOOZE? │───▶│  SLASH  │              │
│                │ -10%    │    │ ✗ FAIL  │              │
│                └─────────┘    └─────────┘              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

1. **Create alarm** with SOL deposit (min 0.001 SOL)
2. **Wake up** before deadline and complete wake-proof
3. **Claim** your deposit back
4. **Or fail** — deposit goes to penalty destination

## Quick Start

### Prerequisites
- Android Studio Hedgehog+
- Rust + Anchor CLI 0.32+
- Solana CLI 1.18+
- Node.js 18+

### Build Android App
```bash
cd apps/android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

### Build & Test Smart Contract
```bash
cd programs/solarma_vault
anchor build
anchor test
```

### Run with Makefile
```bash
make build        # Build everything
make test         # Run all tests  
make deploy-dev   # Deploy to devnet
```

## Architecture

```
solarma/
├── apps/android/          # Kotlin + Compose Android app
│   ├── wallet/            # Solana MWA integration
│   ├── alarm/             # AlarmManager + WorkManager
│   └── wakeproof/         # NFC/QR/Step verification
│
├── programs/solarma_vault/  # Anchor smart contract
│   ├── instructions/      # create, claim, snooze, slash
│   └── state/             # Alarm, Vault, UserProfile
│
└── docs/                  # Architecture & guides
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

### Smart Contract

| Instruction | Description |
|-------------|-------------|
| `initialize` | Create user profile |
| `create_alarm` | Create alarm + deposit SOL to vault |
| `claim` | Claim deposit (after alarm_time, before deadline) |
| `snooze` | Snooze with 10% penalty (doubles each time) |
| `emergency_refund` | Cancel before alarm_time (-5% penalty) |
| `slash` | Permissionless slash after deadline |

**Program ID (Devnet):** `51AEPs95Rcqskumd49dGA5xHYPdTwq83E9sPiDxJapW1`

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for:
- How to set up development environment
- Code style guidelines
- PR process

### Community

- 🐦 Twitter: [@solarma_app](https://twitter.com/solarma_app)
- 💬 Discord: [Coming soon]
- 📧 Email: security@solarma.app (for vulnerabilities)

## Roadmap

### v0.1.0 (Current)
- [x] Core alarm functionality
- [x] Create/Claim/Snooze/Slash instructions
- [x] NFC/QR/Step counter wake-proof
- [x] MWA wallet integration

### v0.2.0 (Planned)
- [ ] SPL token deposits (USDC)
- [ ] Social features (buddy challenges)
- [ ] Streak rewards system
- [ ] Widget for home screen

### v1.0.0 (Mainnet)
- [ ] Security audit
- [ ] Mainnet deployment
- [ ] Play Store release

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md) for responsible disclosure.

## License

Apache-2.0 — see [LICENSE](LICENSE)

---

<p align="center">
  Built with ☀️ for the Solana Seeker community
</p>
