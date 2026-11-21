# EdgeLedger — Native Kotlin + Jetpack Compose Android App (v4.0)

100% native Android multi-sport prediction, odds comparison, and personal tracking app built with **Kotlin 2.1**, **Jetpack Compose (Material 3)**, **Room SQLite**, **DataStore**, **WorkManager**, and **Jetpack Glance**.

## Highlights
- **9 Sports Covered (100% Free & Keyless On-Device Weekly Sync):** Football, NFL, NBA, NHL, MLB, ATP Tennis, Cricket, Formula 1, and MMA.
- **2026-Audited Free Public Feeds:** Football-Data.co.uk (`/mmz4281/` full CSV columns), ClubElo, nflverse, TennisMyLife, 2026-safe ESPN (`site.web.api.espn.com` + fallback), Official NHL Web API, Official MLB Stats API, and Open-Meteo Stadium Weather.
- **Full Multi-Market Engine:** Match Winner, Double Chance, Draw No Bet, Over/Under 0.5–4.5, Both Teams Score, Team Goals, Clean Sheets, Win to Nil, 1st/2nd Half, Half-Time/Full-Time, Combos, Corners, Cards, Red Card Chance, Fouls, Shots, Exact Scores, US Spreads/Totals/Halftime/Overtime, and Tennis 1st Set/Sets/Handicap/Tiebreak/Aces.
- **Live Zero-Restart Theme Studio:** Android 12+ Material You Dynamic Color, AMOLED Pure Black, Light, Dark, Color-Blind Safe presets, 8 Accent Swatches, Sharp/Rounded/Soft corners, Compact/Normal/Spacious density, Text Scale, WCAG 2.1 Contrast Auto-Fix, Undo/Redo, and `.theme.json` export/import.
- **Home-Screen Widget & Weekly Background Sync:** Jetpack Glance widget + WorkManager 7-day automatic background refresh + SHA-256 locked pre-match prediction ledger.

## Building in GitHub Actions (Rolling Overwrite Release)
Push this folder to your GitHub repo's `main` branch. `.github/workflows/android-rolling-release.yml` automatically installs Java 17 and Gradle 8.11.1, runs JUnit 4 unit tests, compiles `edgeledger-android-latest.apk`, and overwrites the single `latest` GitHub Release.
