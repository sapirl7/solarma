# Release Runbook

This runbook is the operator-friendly, step-by-step procedure to release:

- Android APK/AAB
- Solana program (`solarma_vault`)

For checklists, see `docs/RELEASE_CHECKLIST.md`.

## Preflight (Always)

1. Ensure your toolchain matches `docs/TOOLCHAIN.md`.
2. Ensure workspace is clean and on the intended commit.
3. Run:
   - `make lint`
   - `make test`
   - `make audit`
4. Build reproducible artifacts:
   - `./scripts/release-build.sh`
   - Review `dist/**/checksums.txt`.

## Android Release (APK/AAB)

1. Update versioning:
   - `apps/android/app/build.gradle.kts`: bump `versionCode` and `versionName`.
2. Configure signing keys locally:
   - Copy `apps/android/keystore.properties.example` to
     `apps/android/keystore.properties` (never commit).
3. Build release:
   - `cd apps/android && ./gradlew bundleRelease` (AAB)
   - `cd apps/android && ./gradlew assembleRelease` (APK)
4. Verify artifacts:
   - Install on a physical device.
   - Smoke test per `docs/QA_CHECKLIST.md`.
   - Compute SHA256 and record it:
     - `shasum -a 256 app/build/outputs/**/app-*.apk`
5. Publish:
   - If using Play Console: upload AAB, ensure release notes match changelog.

## On-Chain Program Release (Deploy/Upgrade)

1. Confirm target cluster:
   - `solana config get`
2. Confirm the intended Program ID:
   - `programs/solarma_vault/src/lib.rs` (`declare_id!`)
3. Confirm current upgrade authority (record it):
   - `solana program show <PROGRAM_ID> --output json`
4. Build:
   - `cd programs/solarma_vault && anchor build`
5. Deploy/upgrade on the target cluster from a machine holding the upgrade
   authority key (never CI).
6. Verify:
   - `solana program show <PROGRAM_ID>`
   - `anchor idl fetch <PROGRAM_ID> -o /tmp/idl.json` (optional)
7. Smoke tests:
   - Create alarm, snooze once, claim in-window.
   - Try boundary times: `deadline-1`, `deadline`, `deadline+1` (devnet/localnet).

## Tagging + Artifacts

1. Update `CHANGELOG.md`.
2. Create a git tag (convention depends on your release process).
3. Attach:
   - Android artifact(s)
   - `dist/**/checksums.txt`
   - release notes

