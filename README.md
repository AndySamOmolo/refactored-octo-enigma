# EdgeLedger Android — Offline-First Multi-Sport Betting Predictor

Offline-first Android sports probability & value-edge predictor built with **Expo SDK 54**, **React Native**, and **TypeScript**. All data fetching, CSV/JSON parsing, statistical model fitting, walk-forward backtesting, and Kelly stake sizing run **100% locally on your Android device** using free, keyless public data feeds.

## Free Keyless Public Data Feeds (Pulled Directly On-Device)

| Sport | Free Public Sources (No API Key Required) | On-Device Model |
| :--- | :--- | :--- |
| **Football (9 Leagues)** | [Football-Data.co.uk](https://www.football-data.co.uk/data.php) CSVs + [ClubElo](https://clubelo.com/) CSV API + [ESPN Public Scoreboard](https://publicapis.io/espn-sports-api) | Time-Decayed Dixon-Coles (70%) + ClubElo/Elo (30%) |
| **NFL** | [nflverse games.csv](https://github.com/nflverse/nfldata) + ESPN Public Scoreboard | Margin-of-Victory Elo (+48 HFA) |
| **NBA / NHL / MLB** | ESPN Public Keyless Scoreboard JSON | Sport-Specific MoV Elo |
| **ATP Tennis** | [TennisMyLife TML-Database](https://github.com/Tennismylife/TML-Database) CSV + ESPN Public Scoreboard | Surface-Aware Tennis Elo (Hard / Clay / Grass) |

## Offline & Weekly Auto-Sync Behavior

- **Zero-network first boot:** Ships with an embedded multi-sport seed dataset so all 4 tabs (Slate, Offline Simulator, Model Audit, Paper Ledger) work immediately in airplane mode.
- **Weekly auto-update:** When online and the local model state is ≥ 7 days old, the app automatically refreshes all enabled public feeds directly on-device, refits all models locally, and persists the updated state to `AsyncStorage`.
- **Manual sync:** Tap **SYNC NOW** on the Slate screen anytime to refresh immediately. If any individual feed is temporarily offline, the app falls back cleanly to cached/seed history without corrupting local state.

## GitHub Actions Rolling Release (Overwrites Previous Release)

Push this repository to GitHub on the `main` branch. The included workflow (`.github/workflows/android-rolling-release.yml`):
1. Runs `npm run verify` (TypeScript check + automated modeling engine tests + workflow verification).
2. Runs `npx expo prebuild --platform android --clean` and builds `assembleRelease` via Gradle.
3. Force-moves a single `latest` Git tag (`git tag -f latest && git push -f origin refs/tags/latest`) and overwrites the existing `latest` GitHub Release and `edgeledger-android-latest.apk` asset via [`softprops/action-gh-release`](https://github.com/softprops/action-gh-release) so old releases never pile up.

### Optional Release Signing Secrets
To sign the APK with your own upload keystore in GitHub Actions, add these repository secrets under **Settings → Secrets and variables → Actions**:
- `ANDROID_KEYSTORE_BASE64` — Base64-encoded `.keystore` file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Local Verification

```bash
npm install --legacy-peer-deps
npm run verify
```

> **Disclaimer:** EdgeLedger is an analytical probability and paper-trading tool. It does not place real-money wagers or guarantee profits.
# refactored-octo-enigma
# refactored-octo-enigma
