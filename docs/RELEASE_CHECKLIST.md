# Release Checklist

## Pre-Release
- [ ] Update `versionCode` and `versionName` in `apps/android/app/build.gradle.kts`
- [ ] Verify Program ID matches on-chain deployment
- [ ] Run tests: `make test`
- [ ] Run lint: `make lint`
- [ ] Update `CHANGELOG.md`

## Android Release
- [ ] Configure signing keys (local only, never commit)
  - Copy `apps/android/keystore.properties.example` → `apps/android/keystore.properties`
  - Generate keystore:
    - `keytool -genkeypair -v -keystore solarma-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias solarma`
- [ ] Build signed APK: `./gradlew assembleRelease`
- [ ] Verify `app-release.apk` installs and runs
- [ ] Smoke test from `docs/QA_CHECKLIST.md`

## Onchain
- [ ] `anchor build`
- [ ] `anchor test`
- [ ] Deploy to target cluster
- [ ] Confirm IDL updated and matches program

## Publish
- [ ] Tag release in git
- [ ] Attach APK + release notes
