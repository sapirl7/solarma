# QA Checklist (Devnet/Mainnet)

See `docs/QA_MATRIX.md` for device coverage tracking.

## Device & Permissions
- [ ] POST_NOTIFICATIONS granted (Android 13+)
- [ ] Exact alarm permission granted
- [ ] Battery optimization disabled for Solarma
- [ ] NFC available and readable
- [ ] Camera permission for QR
- [ ] Activity recognition permission for steps

## Core Alarm Flow
- [ ] Create alarm without deposit
- [ ] Alarm fires on time (foreground + lock screen)
- [ ] Wake proof succeeds (steps)
- [ ] Alarm stops and marks completed

## Onchain Deposit Flow
- [ ] Create alarm with deposit (0.01 SOL)
- [ ] Pending confirmation handled (network slow)
- [ ] Onchain PDA saved locally
- [ ] Snooze → snoozeCount increments, deposit decreases
- [ ] Claim after alarm_time, before deadline
- [ ] Emergency refund before alarm_time (penalty applied)
- [ ] Missed alarm → slash queued after deadline

## Recovery & Reliability
- [ ] Import onchain alarms from Settings (wallet connected)
- [ ] Imported alarms appear in list
- [ ] Reboot device → alarms restored
- [ ] Offline mode: queue persists, resumes when online

## Network
- [ ] Devnet/Mainnet toggle works
- [ ] RPC fallback triggers on outage
