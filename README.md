<p align="center">
  <img src="docs/assets/solarma_logo.png" alt="Solarma" width="140" />
</p>

<h1 align="center">Solarma</h1>

<p align="center">
  <strong>Stake SOL on your alarm. Wake up or lose it.</strong><br>
  <sub>Built for <a href="https://solanamobile.com/seeker">Solana Seeker</a></sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-Kotlin-7F52FF?style=flat-square&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Blockchain-Solana-14F195?style=flat-square&logo=solana" alt="Solana">
  <img src="https://img.shields.io/badge/Smart_Contract-Anchor-9945FF?style=flat-square" alt="Anchor">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square" alt="License"></a>
  <a href="https://github.com/sapirl7/solarma/actions/workflows/ci.yml"><img src="https://github.com/sapirl7/solarma/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
</p>

> ⚠️ **Currently on Devnet** — This app uses Solana Devnet test tokens. No real SOL is at risk. Get free test SOL at [faucet.solana.com](https://faucet.solana.com).

<p align="center">
  <a href="#features">Features</a> · 
  <a href="#how-it-works">How It Works</a> · 
  <a href="#quick-start">Quick Start</a> · 
  <a href="#architecture">Architecture</a> · 
  <a href="#contributing">Contributing</a> ·
  <a href="#support">Support</a>
</p>

---

## Features

**Commitment-Based Alarm** — Stake SOL to back your wake-up promise

**Native Android** — Optimized for Solana Seeker hardware

**Non-Custodial** — Your keys, your funds via Mobile Wallet Adapter

**Wake Verification** — Prove you're awake with NFC, QR scan, or step counter

**Flexible Penalties** — Choose burn, donate, or send to accountability buddy

**Permissionless Slash** — Anyone can trigger penalty after deadline passes

---

## Screenshots

<p align="center">
  <img src="docs/assets/screenshot_home.png" alt="Home Screen" width="200" />
  <img src="docs/assets/screenshot_create.png" alt="Create Alarm" width="200" />
  <img src="docs/assets/screenshot_deposit.png" alt="Deposit Options" width="200" />
  <img src="docs/assets/screenshot_settings.png" alt="Settings" width="200" />
</p>

---

## How It Works

```mermaid
flowchart LR
    subgraph CREATE["🔒 Create Alarm"]
        A[Set wake time] --> B[Deposit SOL]
    end
    
    subgraph WAKE["⏰ Wake Up"]
        C{Complete proof?}
        C -->|NFC/QR/Steps| D[✅ Verified]
        D --> D2[ack_awake]
        C -->|Snooze| E[10% penalty]
        E --> C
    end
    
    subgraph RESULT["💰 Outcome"]
        F[Claim deposit]
        G[Penalty applied]
    end
    
    B --> C
    D2 --> F
    C -->|Deadline passed| G
    
    style CREATE fill:#14F195,color:#000
    style WAKE fill:#9945FF,color:#fff
    style RESULT fill:#1E1E1E,color:#fff
```

### The Flow

| Step | Action | Result |
|------|--------|--------|
| 1️⃣ | **Create alarm** with SOL deposit | Funds locked in vault |
| 2️⃣ | **Wake up** and complete verification | Prove you're awake |
| 3️⃣ | **Claim** before deadline | Get 100% deposit back |
| ❌ | **Miss deadline** | Penalty applied (burn/donate/buddy) |

> **Snooze penalty:** 10% of remaining deposit × 2^n (doubles each use, compounds on shrinking balance)

---

## Wake Proof Challenges

Choose how to prove you're awake. Each mode requires physical action to dismiss the alarm.

### 🚶 Steps Mode
Walk a required number of steps to dismiss. Uses the phone's pedometer sensor.
- **Target range:** 10–200 steps (configurable)
- **Sensor:** `TYPE_STEP_DETECTOR` for instant response
- **Requires:** `ACTIVITY_RECOGNITION` permission (Android 10+)
- **Tip:** 50+ steps ensures you're truly moving

### 📱 NFC Mode
Tap a pre-registered NFC tag to dismiss. Place it somewhere you need to walk to.
- **Setup:** Register tag in Settings → NFC Tag
- **Use cases:** Bathroom, kitchen, coffee maker
- **Tag types:** Any NFC tag (stickers, cards, keychains)

### 📷 QR Code Mode
Scan a unique QR code generated in the app. Print it and place strategically.
- **Setup:** Generate code in Settings → QR Code
- **Security:** Each code is unique per user
- **Use cases:** Another room, near window, fridge

### ✅ None Mode
Standard alarm without wake proof. No deposit locking, no penalties.
- **Use case:** Regular alarms without commitment
- **Note:** Cannot stake SOL in this mode

---

## Penalty Options

If you fail to wake up, choose where your SOL goes:

| Mode | Icon | Description |
|------|------|-------------|
| **Burn** | 🔥 | SOL permanently destroyed (maximum stakes!) |
| **Donate** | 🎁 | Sent to configured charity address |
| **Buddy** | 👋 | Sent to your accountability partner |

---

## Deposit Amounts

Quick-select or custom input:
- **0.01 SOL** — Light commitment (~$2)
- **0.05 SOL** — Standard stake (~$10)
- **0.1 SOL** — Serious motivation (~$20)
- **0.5 SOL** — High stakes (~$100)
- **Custom** — Any amount you choose


---

## Quick Start

### Prerequisites
- Android Studio Hedgehog or later (JDK 21)
- Rust stable (see `docs/TOOLCHAIN.md`)
- Anchor CLI 0.32.1
- Solana CLI 1.18.26
- Node.js 18+ (npm)

Canonical versions live in `docs/TOOLCHAIN.md`.

### Build Android App
```bash
cd apps/android
./gradlew assembleDebug
```
APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Build Smart Contract
```bash
cd programs/solarma_vault
anchor build
anchor test
```

### Using Makefile
```bash
make build      # Build all components
make test       # Run test suite
make lint       # Run linters (clippy + Android lint)
make audit      # Run security checks
make clean      # Safe cleanup
```

---

## Production Notes

### RPC Provider (Recommended)
For production stability, use a dedicated RPC provider and configure endpoints locally:

```properties
# ~/.gradle/gradle.properties
SOLANA_RPC_DEVNET=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_RPC_MAINNET=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
```

Do not commit real keys.

### Signed Release APK
1. Copy `apps/android/keystore.properties.example` → `apps/android/keystore.properties`
2. Generate keystore:
   `keytool -genkeypair -v -keystore solarma-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias solarma`
3. Build:
   `cd apps/android && ./gradlew assembleRelease`

See `docs/RELEASE_CHECKLIST.md` for the full release flow.

### Reproducible Builds
See `docs/REPRODUCIBLE_BUILDS.md` for deterministic build instructions.

---

## Architecture

```mermaid
graph TB
    subgraph Android["📱 Android App"]
        UI[Jetpack Compose UI]
        VM[ViewModels]
        WP[WakeProof Engine]
        WM[Wallet Manager]
    end
    
    subgraph WakeProof["🔐 Wake Verification"]
        NFC[NFC Scanner]
        QR[QR Scanner]
        STEP[Step Counter]
    end
    
    subgraph Solana["⛓️ Solana Blockchain"]
        MWA[Mobile Wallet Adapter]
        VAULT[Solarma Vault Program]
        PDA[Alarm PDAs]
    end
    
    UI --> VM
    VM --> WP
    VM --> WM
    WP --> NFC & QR & STEP
    WM --> MWA
    MWA --> VAULT
    VAULT --> PDA
    
    style Android fill:#7F52FF,color:#fff
    style WakeProof fill:#14F195,color:#000
    style Solana fill:#9945FF,color:#fff
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed system design.

### Smart Contract Instructions

| Instruction | Description |
|-------------|-------------|
| `initialize` | Create user profile |
| `create_alarm` | Create alarm and deposit SOL to vault |
| `ack_awake` | Record wake proof completion on-chain |
| `claim` | Reclaim deposit after alarm time, before deadline |
| `snooze` | Extend deadline with 10% penalty (doubles each use) |
| `emergency_refund` | Cancel before alarm time with 5% penalty |
| `slash` | Permissionless penalty trigger after deadline |

**Program ID (Devnet)**
```
F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP
```

---

## Roadmap

### Current State
- Smart contract deployed to Devnet
- Android app functional with MWA integration
- Core features: create, claim, snooze, slash, emergency refund
- Wake-proof: NFC, QR, step counter

### Near Term
- Community feedback and bug fixes
- UI/UX improvements based on user testing
- On-chain alarm recovery/import flow

### Mainnet Ready (Criteria)
- [ ] Security audit completed
- [ ] Private RPC with API keys
- [ ] Signed release APK
- [ ] On-chain alarm recovery implemented
- [ ] QA matrix for all wake-proof methods (`docs/QA_MATRIX.md`)

### Future
- SPL token support (Seeker ecosystem tokens)
- Social features and challenges
- iOS port consideration

---

## Contributing

Contributions welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions and guidelines.

---

## Support

See [SUPPORT.md](SUPPORT.md) for community support channels.

---

## Security

Report vulnerabilities responsibly. See [SECURITY.md](SECURITY.md) for disclosure process.

Additional resources:
- `docs/THREAT_MODEL.md`
- `docs/SECURITY_CHECKS.md`

---

## License

Apache-2.0 — see [LICENSE](LICENSE)

---

<p align="center">
  <sub>Open source for the Solana Seeker community</sub>
</p>
