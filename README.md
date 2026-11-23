# EdgeLedger — Native Kotlin + Jetpack Compose Android App (v8.7.0)

100% native, offline-first Android multi-sport analytics, prediction, odds comparison, and personal tracking app built with **Kotlin 2.1**, **Jetpack Compose (Material 3)**, **Room SQLite v5**, **DataStore**, **WorkManager**, **Jetpack Glance**, and **Coil SVG/PNG**.

## Highlights
- **13 Sports Covered (100% Free & Keyless On-Device Sync):** Football (38 leagues via Football-Data.co.uk + OpenLigaDB + ESPN), NFL & NCAAF, NBA, WNBA & NCAAB, NHL (MoneyPuck xG + NHL Web API), MLB (MLB Stats API + ballpark weather/altitude), ATP/WTA Tennis (TennisMyLife + Tennis Abstract Elo), Cricket, Formula 1, MMA (UFC Stats), Rugby Union/League, AFL (Squiggle), Esports (OpenDota), and PGA Golf.
- **Quantitative Prediction & Calibration Engine:** Dixon-Coles bivariate Poisson with league-calibrated `rho`, FiveThirtyEight-style MOV Elo with inter-season mean reversion, external Elo anchoring (World Football Elo, Tennis Abstract), Shin/Power/Additive/Multiplicative/Odds-Ratio de-vig, Fractional Kelly / configurable Flat Unit / % Bankroll staking, 2-way & 3-way Arbitrage/Dutching, and a production-aligned chronological walk-forward backtester reporting Brier Skill Score, Log-Loss, RPS, and ECE.
- **Resilient Offline Architecture:** Atomic quota reservation mutex, `< 512 KB` Room cache with automatic gzipped disk spill, cancellable OkHttp coroutines, Storage Access Framework (SAF) CSV/JSON/Theme backups, Android Keystore AES-256-GCM API key encryption, quiet-hours-aware `+EV` notifications, and Material You Glance home-screen widget with match deep-linking.

## Permanent In-Place Updates (No Reinstall Required)
Android only allows an APK to update an existing installation in-place when both APKs share the exact same **signing certificate**, the same `applicationId` (`com.edgeledger.app`), and a strictly increasing `versionCode` (`807000 + GITHUB_RUN_NUMBER`).

### Zero-Config Automatic In-Place Updates (Ready Out of the Box)
This repository already includes a generated persistent signing keystore (`app/edgeledger-update.jks`, valid for 10,000 days) wired directly into `app/build.gradle.kts` and `.github/workflows/android-rolling-release.yml` — **no GitHub Secrets setup is required**.

1. Push to `main` (or trigger `.github/workflows/android-rolling-release.yml`) and download `edgeledger-android-latest.apk` from the `latest` release.
2. Install this first keystore-signed APK once (replacing any older debug-signed build).
3. Every future rolling build will automatically sign with the exact same certificate and a higher `versionCode`, so Android will **update EdgeLedger in-place** without reinstalling or losing any Room bets, prediction history, or DataStore settings.

> **Optional Google Play Override:** The bundled keystore is intended for personal sideloading and GitHub Releases. If you ever publish to Google Play, run `scripts/setup-release-keystore.sh` and add the 4 `ANDROID_KEYSTORE_*` GitHub Secrets — the workflow automatically prefers your private secrets when present.

## Local Verification
```bash
./gradlew testDebugUnitTest lintDebug
```

> **Disclaimer:** EdgeLedger is strictly an informational sports analytics and personal tracking app. It does not accept wagers or process payments.
