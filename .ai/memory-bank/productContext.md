# Solarma — Product Context

## Wake Proof Types

### Presets
| Preset | Requirements |
|--------|-------------|
| **Light** | 20 steps OR 10s active interaction |
| **Normal** (default) | 50 steps + NFC/QR |
| **Hard** | 80-120 steps + squats/waves + NFC/QR + 20s attention timer |

### Available Conditions
1. **Steps** (primary) — thresholds: 20/50/100/150
2. **NFC** — tap pre-registered tag
3. **QR/Barcode** — scan pre-registered code
4. **Squats** — thresholds: 5/10/20 (phone in pocket)
5. **Waves/movement** — thresholds: 10/20/40

### Fallback Logic
If sensor fails → offer alternative (e.g., accel movement instead of step counter).

---

## Deposit Mechanics

### Penalty Routes
1. **Burn/Sink** — transfer to sink address
2. **Donate** — transfer to allowlisted charity
3. **Buddy** — transfer to friend's wallet

### Snooze
- Decreases remaining deposit per press
- UI shows: cost of next snooze + remaining balance
- Available only before deadline

---

## User Flows

### Evening (Setup)
1. Set wake time
2. Choose Wake Proof preset or custom
3. (Optional) Enable deposit → set amount → choose penalty route
4. Confirm (sign tx if deposit enabled)

### Morning (Challenge)
1. Alarm fires → full-screen lock UI
2. Execute Wake Proof conditions
3. On completion: claim tx (if deposit) or local success

### Failure
- After deadline: anyone can call `slash()` 
- Deposit routes to penalty destination
