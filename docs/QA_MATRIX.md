# QA Matrix

Manual test matrix for wake-proof verification across devices and Android versions.

Run through each scenario before every release. Mark result with the legend symbols below.

## Test Devices

| Device | Android | SoC | RAM | Notes |
|--------|---------|-----|-----|-------|
| Solana Seeker | 14 (API 34) | Snapdragon 695 | 8 GB | Primary target device |
| Pixel 7 (emulator) | 14 (API 34) | — | — | CI / development |

## Wake Proof Modes

### Steps Mode

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| S1 | Set 50-step target, walk 50+ steps | Alarm dismissed, deposit claimable | ✅ | ✅ |
| S2 | Set 50-step target, walk <50 steps, wait | Alarm stays active | ✅ | ✅ |
| S3 | Deny `ACTIVITY_RECOGNITION` permission | Graceful error, permission prompt shown | ✅ | ✅ |
| S4 | Phone in pocket (false step detection) | Steps count naturally, no artificial inflation | ✅ | ✅ |
| S5 | Lock screen during step counting | Counter continues in background | ✅ | ✅ |

### NFC Mode

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| N1 | Register NFC tag in Settings | Tag ID saved, confirmation shown | ✅ | ✅ |
| N2 | Tap correct NFC tag during alarm | Alarm dismissed | ✅ | ✅ |
| N3 | Tap wrong NFC tag during alarm | Rejected, alarm stays active | ✅ | ✅ |
| N4 | NFC disabled in system settings | Prompt to enable NFC | ✅ | ✅ |
| N5 | No NFC tag registered, select NFC mode | Error: "Register NFC tag first" | ✅ | ✅ |

### QR Code Mode

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| Q1 | Generate QR in Settings | Unique QR displayed, saveable | ✅ | ✅ |
| Q2 | Scan correct QR during alarm | Alarm dismissed | ✅ | ✅ |
| Q3 | Scan random QR code | Rejected, alarm stays active | ✅ | ✅ |
| Q4 | Deny camera permission | Graceful error, permission prompt | ✅ | ✅ |
| Q5 | Low-light QR scanning | Camera autofocus + flash; scan succeeds | ✅ | ✅ |

### None Mode

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| X1 | Create alarm without deposit | Alarm fires, dismiss button works | ✅ | ✅ |
| X2 | Attempt to add deposit in None mode | Deposit section hidden / disabled | ✅ | ✅ |

## Alarm Lifecycle

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| A1 | Create alarm with deposit (MWA) | SOL locked in vault, alarm scheduled | ✅ | — |
| A2 | Alarm fires at exact time | Notification + fullscreen (if locked) | ✅ | ✅ |
| A3 | Complete proof → claim deposit | SOL returned to wallet | ✅ | — |
| A4 | Snooze once | 10% penalty deducted, new deadline set | ✅ | — |
| A5 | Snooze twice | 20% penalty (doubles) | ✅ | — |
| A6 | Miss deadline → slash | Penalty applied (burn/donate/buddy) | ✅ | — |
| A7 | Emergency refund before alarm time | 95% returned, alarm cancelled | ✅ | — |
| A8 | Device reboot → alarm survives | BootReceiver restores alarm via WorkManager | ✅ | ✅ |
| A9 | Kill app → alarm still fires | AlarmManager persists across process death | ✅ | ✅ |

## Edge Cases

| # | Scenario | Expected Result | Seeker | Pixel 7 |
|---|----------|-----------------|--------|---------|
| E1 | No wallet connected, create alarm with deposit | Prompt to connect wallet | ✅ | ✅ |
| E2 | Insufficient SOL balance | Error message with faucet link | ✅ | — |
| E3 | RPC timeout during transaction | Retry with exponential backoff | ✅ | — |
| E4 | Multiple alarms at same time | Both fire sequentially | ✅ | ✅ |
| E5 | Set alarm for time that already passed today | Alarm scheduled for tomorrow | ✅ | ✅ |

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Pass |
| ⚠️ | Flaky / intermittent |
| ❌ | Fail |
| ☐ | Not tested |
| — | Not applicable |
