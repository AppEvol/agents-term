# Development, Testing & Releases

Guide for building, testing, and releasing the AgentsTerm Android client (Kotlin + Jetpack Compose + Material 3) and VM daemon (`daemon-rs/`).

## Prerequisites

On a Debian/Ubuntu VM, install all required toolchains in one step:

```bash
sudo ./install_deps.sh       # install missing dependencies (safe to re-run)
./install_deps.sh --check    # verify toolchains without modifying anything
```

Manual requirements:

- **JDK 17 or 21** (Gradle 9.3.1 requires JDK 17+ to run; Java compilation target is configured in `app/build.gradle.kts`).
- **Android SDK** with `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) exported:
  - `platforms;android-36.1` (SDK 36, minor API level 1 / Android 16)
  - `build-tools;36.0.0` (or latest 36.x)
  - `platform-tools` (for `adb`)
- **Gradle 9.3.1+** on `PATH` (this repository pins `9.3.1` in `gradle/wrapper/gradle-wrapper.properties` without committing `gradlew` or `gradle-wrapper.jar`).
- **Rust toolchain** (`rustup`, `cargo`, `rustfmt`, `clippy`, and musl targets `x86_64-unknown-linux-musl` and `aarch64-unknown-linux-musl`) for building and testing `daemon-rs/`.

> **Important:** Do **not** commit `local.properties` or any `*.keystore` files. Set `ANDROID_SDK_ROOT` or `ANDROID_HOME` via the environment instead.

## Debug Signing Keystore

`app/build.gradle.kts` signs debug builds using the first available keystore:

1. `${rootDir}/debug.keystore` (used by CI when decoding `DEBUG_KEYSTORE_BASE64`, or if you place a keystore in your checkout).
2. The main checkout's `debug.keystore` when building from a linked `git worktree`.
3. AGP's default debug signing config (`~/.android/debug.keystore`, generated automatically if missing).

Fresh clones and git worktrees therefore build out of the box with no manual keystore setup.

## Static Checks

Linting, formatting, import order, and dead-code detection for Kotlin, Python, shell scripts, and GitHub workflows run from one script (CI runs the same one). It needs a JDK on `PATH` plus `uv` or `python3 -m venv`, and downloads its pinned tools into `~/.cache/agentsterm-static-checks` on first use:

```bash
scripts/static-checks.sh          # check
scripts/static-checks.sh --fix    # apply automatic fixes, then check
gradle :app:lintDebug             # Android Lint (needs the Android SDK)
```

## Running Tests

### Android Unit & Golden Tests

```bash
# Run all Android unit tests (including Markdown golden tests via Robolectric)
gradle :app:testDebugUnitTest

# Run protocol models, pairing URI parser, WebSocket state reducer, and SSH bootstrap tests
gradle :app:testDebugUnitTest --tests "com.example.ModelsAndStateTest"

# Re-record Markdown golden images on Linux after an intentional rendering change
gradle :app:testDebugUnitTest --tests '*MarkdownGoldenTest*' -Pgoldens=record
```

### VM Daemon & Installer Tests

```bash
cargo test --manifest-path daemon-rs/Cargo.toml
python3 -m unittest discover -s daemon-rs/tests
```

### Multi-Distro Docker VM & Android Emulator E2E Tests

See [`e2e/docker-vm/README.md`](../e2e/docker-vm/README.md) for running headless multi-distro VM daemon E2E suites and Android Emulator ↔ Docker VM instrumented tests.

## Building & Installing the Android APK

### Debug APK

```bash
gradle :app:assembleDebug
```

The compiled debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install onto a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Testing unmerged daemon changes:** Unreleased local/PR APK builds default `BuildConfig.DAEMON_RELEASE_BASE_URL` to `releases/latest/download`. Override it with `-PdaemonReleaseBaseUrl=...` or `AGENTSTERM_DAEMON_RELEASE_BASE_URL` when testing an unmerged daemon binary.

### Signed Release APK / AAB (Optional)

Release builds read signing credentials from environment variables (`app/build.gradle.kts`):

```bash
export KEYSTORE_PATH="/path/to/my-upload-key.jks"
export STORE_PASSWORD="<store-password>"
export KEY_PASSWORD="<key-password>"

gradle :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
gradle :app:bundleRelease     # -> app/build/outputs/bundle/release/app-release.aab
```

## Automatic Verified Releases (GitHub Actions)

Every push to `main` that passes `Static Checks`, `Build APK & Unit Tests`, the VM daemon tests, and `End-to-End Android Instrumented Tests` automatically publishes a new versioned GitHub Release (`v0.3.<run_number>-<short_sha>`) with auto-generated release notes, incrementing Android `versionCode` / `versionName`, and both `AgentsTerm-debug.apk` and `AgentsTerm-v0.3.<run_number>-<short_sha>-debug.apk` attached alongside the `x86_64` and `aarch64` daemon binaries and SHA-256 checksums.

Each official release pins `BuildConfig.DAEMON_RELEASE_BASE_URL` to its own GitHub Release tag so every released APK bootstraps the exact daemon binary built from the same commit.

- **Always-latest verified APK:** `https://github.com/AppEvol/agents-term/releases/latest/download/AgentsTerm-debug.apk`
- **Legacy rolling tag link (also kept in sync):** `https://github.com/AppEvol/agents-term/releases/download/latest/AgentsTerm-debug.apk`
