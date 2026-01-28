# Solarma — System Patterns

## Android Alarm Reliability Pattern

### Core Components
```
AlarmManager.setExactAndAllowWhileIdle()
    ↓
BroadcastReceiver (BOOT_COMPLETED + ALARM_FIRED)
    ↓
Foreground Service (alarm ringing + challenge)
    ↓
Full-screen Activity (over lock screen)
```

### Invariants
- Always register `BOOT_COMPLETED` receiver to restore alarms
- Use `WakeLock` only during challenge (minimal scope)
- Foreground Service notification must be non-dismissible during challenge

---

## Wake Proof Sensor Strategy

### Primary: Step Counter
```kotlin
sensorManager.registerListener(
    stepListener,
    sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER),
    SensorManager.SENSOR_DELAY_NORMAL
)
```

### Fallback Hierarchy
1. `TYPE_STEP_COUNTER` → `TYPE_STEP_DETECTOR`
2. Step detection failed → Accelerometer movement (X seconds threshold)
3. Sensor unavailable → NFC/QR only mode

---

## Onchain State Machine

```
Created ─────────────────────────────┐
    │                                │
    │ claim() [time < deadline]      │ slash() [time >= deadline]
    ↓                                ↓
 Claimed                          Slashed
```

### State Finality
- `Claimed` and `Slashed` are terminal states
- Once finalized, no further mutations allowed

---

## Transaction Queue Pattern

For offline resilience:
1. Build and sign transaction locally
2. Store in SQLite queue with retry count
3. On network restore → send queued transactions
4. UI warns if deadline approaches while offline

---

## Error Recovery Principle

> Never dead-end the user.

Every failure case must have a visible path forward:
- NFC not reading? → Offer QR alternative
- Tx failed? → Retry button + clear error message
- Sensor unavailable? → Fallback condition automatically activated
