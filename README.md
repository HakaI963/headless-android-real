# Headless Android — real Android Studio project

Gradle + Kotlin UI + native C++ (NDK/CMake). The engine that owns virtual
displays, Shizuku shell service, UiAutomation and MCP is Java, compiled by AGP.

## Build

```sh
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

Do not install from Downloads on this phone — the installer gets a parse error
from the FUSE-backed shared storage path. Use:

```sh
bash scripts/install.sh
```

## Structure

- `app/src/main/kotlin/com/headless/android/MainActivity.kt` — Kotlin UI
- `app/src/main/java/com/headless/android/` — engine, Shizuku, MCP, automation
- `app/src/main/cpp/headless.cpp` — native C++ JNI library
- `app/src/main/res/layout/` — XML Material 3 layouts
