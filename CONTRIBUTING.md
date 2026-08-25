# Contributing to Chirp

Thanks for your interest! Chirp is a native Android app for hands-free voice chats
with a self-hosted Ollama server. This guide covers local setup and the conventions
the project follows.

## Prerequisites

- **JDK 17**
- **Android SDK** with platform **35** (Android Studio Ladybug or newer recommended)
- A device or emulator (min SDK 26) for running the app and instrumentation tests
- A reachable Ollama server to actually talk to (see the [README](README.md) for a
  Caddy + HTTPS + Basic Auth reverse-proxy example)

## Getting started

```bash
git clone <your-fork-url>
cd chirp

# The Gradle wrapper JAR is not committed — generate it once
# (Android Studio does this automatically on import):
gradle wrapper

./gradlew assembleDebug
./gradlew :core:test                 # fast JVM unit tests
./gradlew :app:connectedAndroidTest  # Room DAO test (needs a device/emulator)
```

`local.properties` (with `sdk.dir`) is created automatically by Android Studio;
create it manually for headless CLI builds if needed.

## Project layout

Two Gradle modules:

- **`:core`** — pure Kotlin/JVM (no Android). The session state machine, the
  STT/TTS/chat interfaces, the sentence buffer, the Ollama parser, and the Wear
  contract. Unit-tested here.
- **`:app`** — Android. Implements the `:core` interfaces and adds UI, the
  foreground service, audio/Bluetooth routing, persistence, and DI.

See [AGENTS.md](AGENTS.md) for the architecture invariants and gotchas, and the
[README](README.md#architecture) for the annotated package tree.

## Conventions

- **Kotlin official style**, 4-space indentation, trailing commas.
- Keep `:core` free of Android dependencies — it must stay JVM-unit-testable.
- New STT/TTS/chat backends go behind the existing `:core` interfaces with a Hilt
  binding; don't reach around them.
- All session control flows through `ConversationService` action intents → the
  singleton `SessionController`. Add new control paths as service actions.
- Secrets (basic-auth password) belong only in `SettingsRepository`
  (EncryptedSharedPreferences). Never log them.
- Centralize dependency versions in `gradle/libs.versions.toml`; bump the
  Kotlin/KSP/Compose-compiler/AGP/Hilt/Room set together.

## Before opening a pull request

1. `./gradlew :core:test` passes (add/extend tests for logic changes — especially
   the sentence buffer, the Ollama parser, and the session loop).
2. `./gradlew assembleDebug` compiles.
3. If you touched the Room schema, add a real `Migration` (don't rely on the
   destructive fallback in shipped builds).
4. Update [README.md](README.md) and [AGENTS.md](AGENTS.md) if behavior or
   architecture changed.

CI (GitHub Actions) runs `:core:test` and `assembleDebug` on every push and PR.

## Reporting issues

Please include your device/Android version, the Ollama model and server setup
(proxy? HTTPS? Basic Auth?), and logs from `adb logcat` where relevant. Don't paste
real credentials.
