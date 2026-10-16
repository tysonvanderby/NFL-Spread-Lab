# NFL Spread Lab

An Android app project for NFL spread research. The initial screen includes a transparent Elo-style sandbox calculation. It is **not a validated prediction model** and does not yet fetch current schedules, odds, or historical results.

## Build an APK

The GitHub Actions workflow in `.github/workflows/android.yml` builds a debug APK on pushes to `main` and manual workflow runs. After a successful run, open **Actions → Android APK → build → Artifacts** and download `nfl-spread-lab-debug-apk`.

For local builds, use JDK 17, Android SDK platform 35, and Gradle 8.9, then run `gradle assembleDebug`.

## Roadmap

1. Integrate a free schedule/results data source and expose source/update timestamps.
2. Build an Elo model trained only on information available before each historical game.
3. Add walk-forward evaluation and report sample size, ATS results, margin MAE, calibration, and closing-line comparisons.
4. Add upcoming games and market spreads without inventing missing odds.

Model assumptions and data coverage must be shown in the app. Do not describe the model as profitable or validated until evidence supports that claim.
