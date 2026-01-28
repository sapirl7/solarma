# Solarma — Progress

## Phase 1: Bootstrapping ✅

## Phase 2: Vault Contract ✅ (P0 Fixed)
- [x] Instructions: initialize, create_alarm, claim, snooze, slash
- [x] Vault PDA properly initialized
- [x] TooEarly checks in claim/snooze
- [x] close constraints for vault cleanup

## Phase 3: Android Alarm Engine ✅ (P0 Fixed)
- [x] AlarmScheduler, AlarmService, AlarmReceiver
- [x] **CreateAlarmViewModel** — saves to Room + schedules
- [x] **AlarmRepository** — full implementation
- [x] **WakeProofEngine** — enforces completion
- [x] **AlarmActivity** — no bypass, requires proof
- [x] **Notification** — "Stop" removed

## Phase 4: Wallet Integration ⚠️ PARTIAL
- [x] WalletManager, TransactionQueue, RpcClient stubs
- [ ] Tx builder for create_alarm/claim/snooze

## Phase 5: UI ✅
- [x] HomeScreen, CreateAlarmScreen with ViewModel

---

## Remaining P1 Items
- Boot restore via WorkManager
- StepCounter TYPE_STEP_COUNTER
- emergency_refund instruction
- alarm_id in seeds
