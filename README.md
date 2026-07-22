

![Vicious Logo](assets/vicious-logo.png)



© 2026 Love. All rights reserved.

# Vicious Ability

Voice-first personal assistant — Android launcher (Kotlin/Compose) + Termux CLI backend.

Copyright (c) 2026 Alisha Bevis (renaealisha54-debug) — MIT License

---

## Project Root

```
~/Vicious-sandbox/android/ollama/vicious-projects/vicious-ability/
```

## Components

### 1. Termux CLI (voice/command backend)

| File | Path | Purpose |
|---|---|---|
| `vicious_ability.py` | `~/Vicious-sandbox/android/ollama/vicious-projects/vicious-ability/vicious_ability.py` | Main assistant loop. Records mic audio, speaks via TTS, runs built-in and learned commands. |
| `vicious_memory.json` | same directory (generated at runtime) | Stores learned trigger → shell command pairs. Created on first "teach me" interaction. |

**Functions in `vicious_ability.py`:**
- `load_memory()` / `save_memory()` — read/write `vicious_memory.json`
- `speak(text)` — TTS output via `termux-tts-speak`
- `listen()` — records mic audio (`termux-microphone-record`), currently falls back to typed input for transcription
- Main loop — matches input against learned commands first, then built-ins (`hello`, `github`, `chrome`), then offers to learn anything unrecognized

**Requirements:** Termux, Termux:API app, `RECORD_AUDIO` permission granted, Python 3

---

### 2. Android APK (Compose UI launcher)

Root: `vicious-ability-apk/`

| File | Path | Purpose |
|---|---|---|
| `build.gradle` (project) | `vicious-ability-apk/build.gradle` | Top-level Gradle config, plugin classpaths |
| `settings.gradle` | `vicious-ability-apk/settings.gradle` | Declares `:app` module |
| `gradle.properties` | `vicious-ability-apk/gradle.properties` | `android.useAndroidX=true`, `android.enableJetifier=true` |
| `gradlew` / `gradlew.bat` | `vicious-ability-apk/` | Real Gradle wrapper (regenerated via `gradle wrapper --gradle-version 8.7`) |
| `app/build.gradle` | `vicious-ability-apk/app/build.gradle` | Module config: SDK versions, Compose/Material3/lifecycle dependencies |
| `AndroidManifest.xml` | `vicious-ability-apk/app/src/main/AndroidManifest.xml` | Permissions (`RECORD_AUDIO`, `INTERNET`), activity declaration, launcher theme |
| `MainActivity.kt` | `vicious-ability-apk/app/src/main/java/com/renaealisha/viciousability/MainActivity.kt` | Single Compose screen: command list, "Start Voice Mode" button that launches Termux + runs the CLI |
| `colors.xml` | `vicious-ability-apk/app/src/main/res/values/colors.xml` | Legacy color resources (unused by Compose theme directly, kept for resource compatibility) |

**Functions in `MainActivity.kt`:**
- `MainActivity.onCreate()` — sets Compose content
- `ViciousAbilityHome(activity)` — renders title, command reference list, setup instructions, and the Termux launch button
- Button `onClick` — builds an `Intent` targeting `com.termux.app.TermuxActivity` with `com.termux.execute` extra to `cd` into the project dir and run `vicious_ability.py`

**Requirements:** JDK 17, Gradle 8.7 wrapper, Android Gradle Plugin 8.5.0, Kotlin 1.9.24, Compose compiler extension 1.5.14, Compose/Material3 libs pinned to 1.7.0/1.3.0, `compileSdk`/`targetSdk` 35, `minSdk` 24

---

## Known Gaps / Open Work

- **No real speech-to-text** — `listen()` records audio but doesn't transcribe it; falls back to typed input
- **Hardcoded Termux path** in `MainActivity.kt` must match the real device path (corrected to `~/Vicious-sandbox/android/ollama/vicious-projects/vicious-ability`)
- **APK is a launcher, not a standalone assistant** — voice mode only runs inside Termux, not natively in-app
- App files were briefly lost mid-session during a directory cleanup and recreated from scratch — verify `find app/src -type f` shows all three source files before building

## Build

```bash
cd vicious-ability-apk
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`
