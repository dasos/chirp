# Chirp

<!-- After pushing, replace OWNER/REPO below with your GitHub path so the CI badge resolves. -->
[![CI](https://github.com/dasos/chirp/actions/workflows/ci.yml/badge.svg)](https://github.com/dasos/chirp/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A native Android app for **hands-free voice conversations with a self-hosted Ollama server**, designed for use while walking with Bluetooth headphones.

Speak → on-device speech-to-text → stream the reply from Ollama → speak it back **sentence-by-sentence** as it arrives → automatically listen again. The loop runs in a foreground service so it survives screen-off, and headset media buttons pause/resume it.

- **Kotlin + Jetpack Compose** (Material 3, dynamic color, dark mode)
- **No custom backend** — the app talks directly to Ollama (`/api/chat`, `/api/tags`, `/api/version`)
- Coroutines + Flow, MVVM, Hilt, Room, OkHttp (NDJSON streaming) + kotlinx.serialization
- On-device `SpeechRecognizer` (STT) and `TextToSpeech` (TTS), behind clean interfaces so server-side Whisper/Piper can be dropped in later
- Min SDK 26, target/compile SDK 35

---

## Table of contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Architecture](#architecture)
- [Build & run](#build--run)
- [Reverse proxy: Caddy + HTTPS + Basic Auth in front of Ollama](#reverse-proxy-caddy--https--basic-auth-in-front-of-ollama)
- [In-app configuration](#in-app-configuration)
- [Permissions](#permissions)
- [Testing](#testing)
- [Phase 2: Wear OS companion (architected, not built)](#phase-2-wear-os-companion-architected-not-built)
- [Known limitations](#known-limitations)
- [Project status](#project-status)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- 🎙️ **Hands-free loop** — speak, hear the reply, and it listens again automatically; keep your phone in your pocket while walking.
- ⚡ **Low-latency speech** — the reply is spoken **sentence-by-sentence** as it streams in, instead of waiting for the whole response.
- 🎧 **Bluetooth-aware** — routes the mic over Bluetooth SCO when a headset is connected, requests audio focus, and maps **headset media buttons** to pause/resume and stop speaking.
- 🔔 **Survives screen-off** — a foreground service keeps the session alive, with a persistent notification showing state (Listening / Thinking / Speaking / Paused), the latest partial reply, an elapsed "Thinking…" timer, and Pause/Resume + Stop actions.
- 🔒 **Your server, your rules** — talks directly to Ollama with an optional **HTTP Basic Auth** header on every request (for a reverse proxy), and requires HTTPS for non-local hosts. Credentials are stored in `EncryptedSharedPreferences`.
- 💾 **History** — conversations and messages persist locally (Room), with auto-generated titles, swipe-to-delete, and tap-to-continue.
- ⌨️ **Type-instead-of-speak** fallback for noisy environments.
- 🗣️ **Spoken errors** — "Connection lost", "I didn't catch that", etc., with retry/backoff — because you're not looking at the screen.
- 🎨 **Polished UI** — Material 3 with dynamic color, dark mode, an animated central mic/status indicator, and haptics on listen start/stop.
- 🧩 **Swappable speech** — STT/TTS sit behind clean interfaces, so server-side Whisper/Piper can replace the on-device engines without touching the rest of the app.

## Screenshots

> _Add screenshots/GIFs here once you've run the app — e.g. the home list, a live conversation with the pulsing mic indicator, and the settings screen. Drop images in `docs/` and reference them like `![Conversation](docs/conversation.png)`._

---

## Architecture

Two Gradle modules keep the portable session logic free of Android so it is unit-testable and reusable by the future Wear OS module.

```
:core   (pure Kotlin/JVM — no Android deps)
  model/      Role, Message, Conversation
  session/    SessionPhase, SessionState, SessionCommand, SessionEvent,
              SessionController  ← the hands-free loop (state machine)
              ConversationStore, SettingsProvider  (interfaces the app implements)
  speech/     SpeechToTextEngine, TextToSpeechEngine (interfaces), SentenceBuffer
  chat/       ChatClient (interface), ChatStreamEvent, Ollama DTOs, OllamaStreamParser
  wear/       WearContract  ← Phase 2 Data Layer paths + (de)serialization
  util/       DispatcherProvider, Clock

:app    (Android)
  data/local/      Room: entities, DAOs, ChirpDatabase
  data/repository/ ConversationRepository       (implements ConversationStore)
  data/settings/   SettingsRepository            (EncryptedSharedPreferences; implements SettingsProvider)
                   ConnectionConfigHolder        (current server URL + auth, read per request)
  network/         OllamaChatClient (implements ChatClient), AuthInterceptor (basic auth + HTTPS-for-remote)
  speech/          AndroidSpeechToText, AndroidTextToSpeech
  audio/           AudioRouteManager (focus + Bluetooth SCO), MediaSessionController (headset buttons)
  service/         ConversationService (foreground), ConversationNotification
  ui/              theme, navigation, home, conversation, settings, components, permissions
  di/              Hilt modules (bind :core interfaces → Android impls)
```

### The loop

`SessionController` (in `:core`) is the single source of truth. It runs one long-lived coroutine:

> **listen** → transcript → **stream** from Ollama → feed tokens to `SentenceBuffer` → **speak** each complete sentence as soon as it's ready (streaming continues while speaking) → **auto-listen** again.

Control actions (pause / resume / stop / stop-speaking / submit-text) interrupt the loop by cancelling and relaunching it from a clean point, with intent preserved in fields — this avoids fragile self-cancellation of an in-flight turn. State is exposed as a `StateFlow<SessionState>`; one-off effects (haptics) as a `SharedFlow<SessionEvent>`.

### Control flow

Every entry point funnels through the **foreground service** as an action intent, so there is one path to the controller:

```
UI (ConversationViewModel)  ─┐
Notification buttons         ├─►  ConversationService (action intents)  ─►  SessionController
Headset media buttons        │         (audio focus + SCO + notification)
(Phase 2) Wear Data Layer  ──┘
```

The `ConversationService` adds the Android concerns the pure controller shouldn't know about: audio focus, Bluetooth SCO routing (`AudioRouteManager`), headset media buttons (`MediaSessionController`), and the persistent notification.

### Networking

`OllamaChatClient` posts to `/api/chat` with `stream: true` and reads the NDJSON response **line-by-line** via OkHttp + Okio, mapping each line with the pure `OllamaStreamParser`. `AuthInterceptor` attaches `Authorization: Basic …` to **every** request when credentials are configured, and refuses plaintext HTTP to non-local hosts.

---

## Build & run

Requirements: **JDK 17**, the **Android SDK** (platform 35), and Android Studio (Ladybug or newer) or a local Gradle 8.11+.

> **Gradle wrapper jar:** this repository ships the wrapper *config* (`gradle/wrapper/gradle-wrapper.properties`) but not the binary `gradle-wrapper.jar`. Opening the project in Android Studio generates it automatically. From the command line, run `gradle wrapper` once (with a locally installed Gradle) to create `gradlew` + the jar, then use `./gradlew` as below.

```bash
# Android Studio: File ▸ Open ▸ select this directory, let it sync, then Run ▸ app.

# Command line:
gradle wrapper            # one-time, if you don't already have ./gradlew + the jar
./gradlew assembleDebug   # build the debug APK
./gradlew installDebug    # build + install on a connected device/emulator

# Tests:
./gradlew :core:test                 # JVM unit tests (sentence buffer, parser, controller)
./gradlew :app:connectedAndroidTest  # Room DAO instrumentation test (needs a device/emulator)
```

`local.properties` (pointing `sdk.dir` at your Android SDK) is created automatically by Android Studio; create it manually for CLI builds if needed.

First launch:

1. Open **Settings** (gear icon).
2. Enter your **Server URL** (e.g. `https://ollama.example.com`) and, if your proxy uses it, the **Basic auth** username/password.
3. Tap **Test connection**, then pick a **Model** (the list is fetched from `/api/tags`).
4. Go back, tap **New conversation**, then tap the mic and start talking.

---

## Reverse proxy: Caddy + HTTPS + Basic Auth in front of Ollama

Ollama has no built-in auth and binds to `127.0.0.1:11434` by default. Put [Caddy](https://caddyserver.com/) in front of it to add TLS and HTTP Basic Auth — Chirp then talks to Caddy over HTTPS and sends the `Authorization` header on every request.

**1. Generate a bcrypt password hash:**

```bash
caddy hash-password --plaintext 'your-strong-password'
# → $2a$14$abcd...   (copy this hash)
```

**2. `Caddyfile`:**

```caddyfile
ollama.example.com {
    # Caddy v2.8+ uses `basic_auth`; older versions use `basicauth`.
    basic_auth {
        # <username>  <bcrypt-hash-from-step-1>
        walker $2a$14$abcd...replace-with-your-hash...
    }

    reverse_proxy 127.0.0.1:11434 {
        # Stream NDJSON tokens through immediately instead of buffering,
        # so Chirp can start speaking the first sentence with low latency.
        flush_interval -1

        # Ollama checks the Host header; present itself as a local client.
        header_up Host {upstream_hostport}
    }
}
```

- For a **public domain**, Caddy automatically provisions a Let's Encrypt certificate.
- For a **LAN-only** setup, use a hostname you control plus Caddy's internal CA (`tls internal`) — and install Caddy's root cert on the phone — or terminate TLS with a real certificate for a domain that resolves to your LAN IP.
- Run it: `caddy run` (or `caddy start`). Point Chirp's Server URL at `https://ollama.example.com`.

> **Local development without a proxy:** you can point Chirp directly at `http://<lan-ip>:11434` (start Ollama with `OLLAMA_HOST=0.0.0.0`). Chirp permits plaintext HTTP only for local/private hosts; remote hosts must be HTTPS.

---

## In-app configuration

All settings persist in `EncryptedSharedPreferences` (so the basic-auth password is encrypted at rest):

| Setting | Notes |
|---|---|
| Server URL | `https://…` required for non-local hosts |
| Basic auth username / password | Optional; sent as `Authorization: Basic` on every request |
| Model | Fetched from `GET /api/tags`; refreshable |
| System prompt | Sent as the leading `system` message |
| Speaking speed | TTS rate, 0.5×–2.0× |
| Voice | Installed `TextToSpeech` voices |
| Auto-listen | Toggle the hands-free loop vs. tap-to-talk |
| Listening silence timeout | Advisory STT end-of-speech silence |

---

## Permissions

Requested at runtime, when first needed:

- `RECORD_AUDIO` — required for speech recognition (requested before the first listen).
- `POST_NOTIFICATIONS` — for the ongoing session notification (requested at startup, Android 13+).
- `BLUETOOTH_CONNECT` — requested alongside the mic (Android 12+) for SCO routing.

Declared (no runtime prompt): `INTERNET`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.

---

## Testing

- **`:core` unit tests** (pure JVM, fast):
  - `SentenceBufferTest` — sentence boundary detection: confirmed terminators, decimals (`3.14`), abbreviations/initials/acronyms, newlines, and the max-length flush.
  - `OllamaStreamParserTest` — token / done / error / blank / unknown-field NDJSON lines.
  - `SessionControllerTest` — full turn via injected text (asserts persistence + sentence-by-sentence speech), stop→idle, and stream-failure→retry→"Connection lost" without persisting an empty reply. Uses fakes + a test dispatcher.
- **`:app` instrumentation test**: `ConversationDaoTest` — insert/read, ordering, user-message count, and `ON DELETE CASCADE`.

```bash
./gradlew :core:test
./gradlew :app:connectedAndroidTest
```

---

## Phase 2: Wear OS companion (architected, not built)

The watch is intended as a thin remote: render `SessionState`, send `SessionCommand`s. The pieces are already in place:

- `:core/wear/WearContract.kt` defines the **Data Layer** paths (`/chirp/state`, `/chirp/command`), the capability name, and (de)serialization of state/commands. Both the phone and a future `:wear` module depend on `:core`, so they share this vocabulary.
- Session control already funnels through `ConversationService` action intents — the watch path is just "Data Layer message → decode with `WearContract` → start the service with the matching action." The hook points are marked with `PHASE 2` comments in `WearContract`, `SessionController`, and `MediaSessionController`.

To add it later: create a `:wear` module (uncomment the include in `settings.gradle.kts`), depend on `:core`, add a `WearableListenerService` on the phone that publishes `SessionController.state` to `/chirp/state` and forwards `/chirp/command` messages into the service, and a Wear Compose UI that mirrors `SessionState` and sends commands.

---

## Known limitations

- **Bluetooth audio uses SCO for the whole session** (not A2DP). SCO is mono/narrowband, so TTS quality over Bluetooth is "phone-call" grade rather than music-grade. This is the trade-off for using the headset microphone hands-free; per-turn SCO toggling would improve playback quality at the cost of ~1–2s of latency each turn. TTS routing falls back to loud media output when no SCO headset is present.
- **On-device STT** (`SpeechRecognizer`) quality and offline availability vary by device/OEM; some recognizers require network. The `SpeechToTextEngine` interface exists so a server-side Whisper engine can replace it.
- **Mid-stream network drops are not resumed**: retries with backoff happen only before any tokens arrive (Ollama can't resume a partial generation, and re-requesting would duplicate already-spoken text). After tokens start, a drop ends the turn with a spoken "Connection lost" and keeps whatever was received.
- **Sentence splitting is heuristic.** It handles decimals, common abbreviations, initials and dotted acronyms, but unusual punctuation may split imperfectly; a long unpunctuated stream is flushed at word boundaries so speech never stalls.
- **`fallbackToDestructiveMigration()`** is used for the v1 Room database — fine for a single-version app, but add real migrations before shipping schema changes.
- **The Gradle wrapper jar is not committed** (see [Build & run](#build--run)); Android Studio or `gradle wrapper` generates it. GitHub Actions CI compiles the app and runs the tests on every push.

---

## Project status

Phase 1 (everything above) is implemented end to end. Phase 2 (the Wear OS companion) is **architected but not built** — the shared contract and integration points exist; see [above](#phase-2-wear-os-companion-architected-not-built). This is a personal/self-hosted project; contributions and issues are welcome.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, conventions, and the PR checklist, and [CLAUDE.md](CLAUDE.md) for the architecture invariants and gotchas. In short: keep `:core` Android-free, route session control through the service, and run `./gradlew :core:test` before opening a PR.

## License

[MIT](LICENSE) © 2026 dasos.
