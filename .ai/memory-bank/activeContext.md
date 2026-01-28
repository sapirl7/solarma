# Solarma — Active Context

## Current Focus
**P0: Close MVP Vertical Slice**

The app has structure but is not production-correct:
- CreateAlarm UI exists but doesn't save to DB
- Wake Proof UI exists but can be bypassed
- Vault PDA is not initialized (will fail on-chain)
- Time rules not enforced

## Active Decisions
| Decision | Status |
|----------|--------|
| Vault as program-owned Account vs SystemAccount | Pending ADR |
| alarm_id counter vs timestamp in seeds | Pending |
| Emergency refund mechanism | P1, design needed |

## Files Being Modified
- `ui/create/CreateAlarmViewModel.kt` — NEW
- `alarm/AlarmRepository.kt` — rewrite from stub
- `wakeproof/WakeProofEngine.kt` — NEW
- `alarm/AlarmActivity.kt` — remove bypass
- `alarm/AlarmService.kt` — remove "Stop" action
- `instructions/create_alarm.rs` — add vault init
- `instructions/claim.rs` — add time check
- `instructions/snooze.rs` — add time check

## Risks
- Android 12+ background restrictions on AlarmManager
- MWA wallet availability on Seeker device
- Sensor availability for step counting

## Recent Commands
```bash
git push  # Phase 5 pushed but incomplete
```
