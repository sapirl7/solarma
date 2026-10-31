# Configuration Reference

## Environment Variables

### Android App (Gradle properties)
The network is fixed per build; there is no runtime network switch in the app.

| Property | Default | Description |
|----------|---------|-------------|
| `SOLARMA_NETWORK` | `devnet` | `devnet` or `mainnet`. Selects MWA cluster, RPC set and UI (devnet banner/faucet hidden on mainnet). |
| `SOLARMA_PROGRAM_ID` | devnet ID | Program ID the app talks to. **Required** for mainnet. |
| `SOLARMA_TREASURY` | devnet address | Destination for the Donate penalty route. **Required** for mainnet. |
| `SOLANA_RPC_DEVNET` | public devnet | Comma-separated Devnet RPC endpoints |
| `SOLANA_RPC_MAINNET` | — | Comma-separated Mainnet RPC endpoints. **Required** for mainnet. |

A mainnet build fails if any required property is missing, malformed, or equal to
a known-compromised devnet address.

```bash
./gradlew assembleRelease -PSOLARMA_NETWORK=mainnet \
  -PSOLARMA_PROGRAM_ID=<program id> -PSOLARMA_TREASURY=<address> \
  -PSOLANA_RPC_MAINNET=<private rpc url>
```

Note: RPC URLs are compiled into the APK, so any API key in them is public.
Use a key restricted to the app (allowed origins / rate limits) at your provider.

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
| `default_steps` | Default step target for wake proof |
| `default_deposit_sol` | Default SOL deposit |
| `default_penalty` | 0=Burn, 1=Donate, 2=Buddy |
| `nfc_tag_hash` | Stored NFC tag hash (local only) |
| `qr_code` | Stored QR code (local only) |
