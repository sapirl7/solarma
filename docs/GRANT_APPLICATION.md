# Solarma — Solana Foundation Grant Application

## Project Overview

**Solarma** is a Solana-native Android alarm app that combines physical wake proof challenges with onchain financial commitment. Users stake SOL when setting alarms and must complete challenges (walking steps, scanning NFC tags) to reclaim their deposit.

### The Problem

Traditional alarm apps are easily dismissed. Snooze addiction affects millions of people, leading to:
- Chronic lateness
- Reduced productivity  
- Sleep schedule disruption

### Our Solution

Solarma adds real stakes to waking up:

1. **Financial Commitment**: Stake 0.01–0.5 SOL when setting an alarm
2. **Physical Proof**: Complete challenges that require you to actually get out of bed
3. **Consequences**: Fail to wake up → lose your deposit (burned, donated, or sent to a friend)

## Technical Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App                             │
├───────────────┬───────────────┬───────────────────────────────┤
│ Alarm Engine  │ Wake Proof    │ Wallet Integration            │
│ - AlarmManager│ - StepCounter │ - Mobile Wallet Adapter       │
│ - Foreground  │ - NFC Scanner │ - Anchor Tx Builder           │
│   Service     │ - QR Scanner  │ - Transaction Queue           │
└───────────────┴───────────────┴───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Solana (Anchor)                           │
├──────────────────────────────────────────────────────────────┤
│ • create_alarm: Create alarm with SOL deposit                │
│ • claim: Return deposit after completing wake proof          │
│ • snooze: Reduce deposit for extra time (10% fee)           │
│ • slash: Permissionless transfer after deadline              │
│ • emergency_refund: Cancel before alarm time (5% fee)        │
└──────────────────────────────────────────────────────────────┘
```

## Why Solana?

1. **Mobile-First**: Solana Mobile Stack (SMS) provides native wallet integration
2. **Low Fees**: <$0.001 per transaction enables micro-deposits
3. **Speed**: Sub-second finality for real-time claim verification
4. **Ecosystem**: Integration with Phantom, Solflare, Saga wallet

## Current Status

### ✅ Completed (MVP)

| Phase | Status | Details |
|-------|--------|---------|
| Anchor Program | ✅ | 6 instructions, tested on devnet |
| Android Core | ✅ | Alarm engine, wake proof, Room DB |
| Wallet Integration | ✅ | MWA, transaction builder, RPC client |
| UI | ✅ | Home screen, create alarm flow |
| Testing | ✅ | Anchor tests, Android unit tests |

### 🎯 Roadmap

**Q1 2026 (Grant Period)**
- [ ] Closed beta on Solana devnet
- [ ] iOS version (React Native or SwiftUI)
- [ ] Social features (buddy accountability)

**Q2 2026**
- [ ] Mainnet launch
- [ ] SPL token support (USDC deposits)
- [ ] Leaderboards and streaks

**Q3 2026**
- [ ] Premium features (custom challenges)
- [ ] B2B: Team accountability for remote workers

## Budget Request

| Category | Amount (SOL) | Description |
|----------|--------------|-------------|
| Development | 50 | iOS port, smart contract audits |
| Infrastructure | 10 | RPC nodes, backend services |
| Testing | 10 | Device testing, QA |
| Marketing | 20 | Launch campaign, influencers |
| Reserve | 10 | Contingency |
| **Total** | **100 SOL** | |

## Team

**Lead Developer**: Full-stack engineer with experience in Android, Solana, and fintech applications.

## Links

- **GitHub**: https://github.com/sapirl7/solarma
- **Demo Video**: [Coming Soon]
- **Technical Docs**: See README.md

## Unique Value Proposition

1. **First-to-Market**: No existing Solana alarm app with commitment mechanism
2. **Viral Potential**: "Buddy" penalty route creates social sharing
3. **Real Utility**: Solves a genuine problem with crypto-native solution
4. **Retention**: Daily use creates strong user habits

## Success Metrics

| Metric | 3-Month Target | 6-Month Target |
|--------|----------------|----------------|
| Daily Active Users | 500 | 5,000 |
| Total Deposits | 100 SOL | 1,000 SOL |
| Wake Success Rate | 85% | 90% |
| App Store Rating | 4.5★ | 4.7★ |

---

## Contact

Ready to discuss further and provide demo access.

**Project**: Solarma  
**Category**: Consumer Mobile + DeFi  
**Requested Amount**: 100 SOL
