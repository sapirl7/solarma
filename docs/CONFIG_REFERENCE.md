# Configuration Reference

## Environment Variables

### Android App (local.properties / BuildConfig)
| Variable | Required | Description |
|----------|----------|-------------|
| `SOLANA_RPC_URL` | Yes | RPC endpoint URL |
| `SOLANA_NETWORK` | Yes | mainnet-beta / devnet / localnet |
| `DEBUG_LOGGING` | No | Enable verbose logs (default: false) |

### Anchor Program
| Variable | Required | Description |
|----------|----------|-------------|
| `ANCHOR_PROVIDER_URL` | Yes | RPC URL for deployments |
| `ANCHOR_WALLET` | Yes | Path to keypair file |

## Build Configurations

### Android (build.gradle.kts)
```kotlin
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(...)
        }
    }
}
```

### Anchor (Anchor.toml)
```toml
[provider]
cluster = "devnet"
wallet = "~/.config/solana/id.json"

[programs.devnet]
solarma_vault = "PROGRAM_ID_HERE"
```

## Alarm Configuration

### Wake Proof Presets
```yaml
light:
  steps: 20
  alternatives: ["interaction_10s"]

normal:
  steps: 50
  nfc: required
  alternatives: ["qr"]

hard:
  steps: 100
  nfc: required
  squats: 10
  attention_timer: 20s
```

### Snooze Models
```yaml
linear:
  cost_per_snooze: 10%  # of remaining

exponential:
  base_cost: 5%
  multiplier: 2  # doubles each snooze
```

## Feature Flags

| Flag | Default | Description |
|------|---------|-------------|
| `ENABLE_DEPOSIT` | true | Allow onchain deposits |
| `ENABLE_SQUATS` | false | Enable squat detection (Phase 2) |
| `ENABLE_SEEKER_CHECK` | false | Require Seeker device (Phase 5) |
