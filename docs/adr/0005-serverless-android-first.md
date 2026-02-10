# ADR-0005: Serverless, Android-First Architecture

**Status:** Accepted  
**Date:** 2026-01-28  
**Author:** sapirl7

## Context

Solarma needs a client. Options range from a web app, to a cross-platform
mobile app, to a native Android app. The backend could be a traditional
server, a serverless function layer, or nothing at all.

## Decision

- **Native Android** (Kotlin + Jetpack Compose) as the sole client.
- **Zero backend** — the app talks directly to Solana RPC. No server, no
  database, no API keys to manage.
- **Mobile Wallet Adapter (MWA)** for signing — the app never touches
  private keys.

## Rationale

- **Solana Seeker.** Solarma targets the Solana Mobile Seeker device. Native
  Android is the only option for the Solana dApp Store. React Native and
  Flutter add layers that complicate MWA integration.
- **Non-custodial by architecture.** Without a server, there's no custodial
  risk and no server to hack. The user's wallet app holds the keys.
- **Operational simplicity.** One developer, one week. Maintaining a server
  doubles the attack surface and ops burden. Serverless means zero
  infrastructure cost and zero uptime concerns.
- **Privacy by default.** No server = no user data collection. The only
  "data" is the on-chain alarm state, which is public by design (Solana).

## Consequences

- **No push notifications.** Without a server, we can't push "your alarm is
  about to fire." The app uses Android WorkManager for local scheduling.
- **No analytics.** We cannot track DAU, retention, or funnel metrics
  server-side. On-chain events + `monitor_program_events.cjs` provide a
  partial substitute.
- **RPC dependency.** The app depends on public or configured RPC endpoints.
  Devnet RPCs are rate-limited — the app uses configurable endpoints with
  exponential backoff.
- **Single platform.** iOS users cannot use Solarma. This is acceptable
  for the Seeker-first positioning.

## Alternatives Considered

- **React Native + Expo:** faster cross-platform development, but MWA
  integration is less mature and dApp Store requires native Android.
- **Web app (Next.js):** wider reach, but doesn't work with Seeker/MWA2
  and can't be listed on the dApp Store.
- **Backend with Firebase/Supabase:** enables push notifications and
  analytics, but introduces custodial risk perception and operational
  complexity. Rejected for v1.
