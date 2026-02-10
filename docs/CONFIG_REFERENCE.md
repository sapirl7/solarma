# Configuration Reference

## Environment Variables

### Android App (Gradle properties)
Optional overrides (comma-separated endpoints):

| Property | Description |
|----------|-------------|
| `SOLANA_RPC_DEVNET` | Override Devnet RPC endpoints |
| `SOLANA_RPC_MAINNET` | Override Mainnet RPC endpoints |
| `SOLARMA_ATTESTATION_SERVER_URL` | Optional attestation server base URL (Attested Mode ACK permits) |

Set in `~/.gradle/gradle.properties` or project `gradle.properties`.

Example:
```properties
SOLANA_RPC_DEVNET=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_RPC_MAINNET=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
```

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

## Runtime Settings (Android DataStore)

| Key | Description |
|-----|-------------|
| `is_devnet` | Use Devnet (true) or Mainnet (false) |
| `default_steps` | Default step target for wake proof |
| `default_deposit_sol` | Default SOL deposit |
| `default_penalty` | 0=Burn, 1=Donate, 2=Buddy |
| `nfc_tag_hash` | Stored NFC tag hash (local only) |
| `qr_code` | Stored QR code (local only) |
| `attested_mode` | If true, queue `ACK_AWAKE_ATTESTED` instead of `ACK_AWAKE` |
