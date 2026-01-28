# Solarma — Project Brief

## Overview
Solarma is a Seeker-first Android alarm application with optional Solana onchain commitment mechanics. The user sets an alarm, chooses a "Wake Proof" challenge (steps, NFC, squats), and optionally deposits SOL/USDC. Morning success = claim deposit back. Failure = deposit slashed to a chosen route (burn/donate/buddy).

## Core Value Proposition
- **Daily onchain engagement** tied to a real-world habit
- **Seeker-native** experience (biometrics, fast signing, NFC)
- **Gamified accountability** without requiring friends or groups

## Non-Negotiable Requirements
1. Alarm must work reliably (Doze mode, after reboot, app killed)
2. Wake Proof runs 100% on-device
3. Sensory failures must offer fallbacks (never lose deposit due to hardware)
4. Onchain logic is permissionless and auditable
5. No private keys on server; no raw sensor data uploaded

## Target Audience
Early Solana mobile users who want to build habits with skin in the game.

## Success Metrics (MVP)
- Alarm fires reliably in 99%+ of cases
- Wake Proof completion rate >90% per attempt
- Claim transaction success rate >95% when online
