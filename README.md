# Chirp

<!-- After pushing, replace OWNER/REPO below with your GitHub path so the CI badge resolves. -->
[![CI](https://github.com/dasos/chirp/actions/workflows/ci.yml/badge.svg)](https://github.com/dasos/chirp/actions/workflows/ci.yml)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A native Android app for **hands-free voice conversations with AI models via [OpenRouter](https://openrouter.ai)** — or any OpenAI-compatible endpoint — designed for use while walking with Bluetooth headphones.

Speak → speech-to-text → stream the reply from the chat backend → speak it back **sentence-by-sentence** as it arrives → automatically listen again. The loop runs in a foreground service so it survives screen-off, and the big push-to-talk button walks it (talk → hear → talk again).

- **Kotlin + Jetpack Compose** (Material 3, dynamic color, dark mode)
- **OpenRouter by default, nothing else required** — the app talks directly to `https://openrouter.ai/api/v1` (`POST /chat/completions`, `GET /models`); any OpenAI-compatible gateway (e.g. a self-hosted LiteLLM box) works via the advanced base-URL setting
- Optional **server-side web search** (OpenRouter's `openrouter:web_search` tool) so replies can be grounded in current web results
- Coroutines + Flow, MVVM, Hilt, Room, OkHttp (SSE streaming) + kotlinx.serialization
- App-owned microphone pipeline for STT (`AudioRecord` + Silero VAD + `/audio/transcriptions`) and on-device `TextToSpeech` (TTS), behind clean interfaces
- Min SDK 26, target/compile SDK 35

---

## Table of contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Architecture](#architecture)
- [Build & run](#build--run)
- [API access: keys, gateways and HTTPS](#api-access-keys-gateways-and-https)
- [In-app configuration](#in-app-configuration)
- [Permissions](#permissions)
- [Testing](#testing)
- [Wear OS companion](#wear-os-companion)
- [Known limitations](#known-limitations)
- [Project status](#project-status)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- 🎙️ **Hands-free loop** — speak, hear the reply, and it listens again automatically; keep your phone in your pocket while walking.
- ⚡ **Low-latency speech** — the reply is spoken **sentence-by-sentence** as it streams in, instead of waiting for the whole response.
- 🎧 **Bluetooth-aware** — routes the mic over Bluetooth SCO when a headset is connected and requests audio focus, so the headset microphone drives the conversation.
- 🔔 **Survives screen-off** — a foreground service keeps the session alive, with a persistent notification showing state (Listening / Thinking / Speaking / Ready), the latest partial reply, an elapsed "Thinking…" timer, and Stop (+ Stop speaking) actions.
- 🔒 **Your key, your rules** — the API key is stored in `EncryptedSharedPreferences`, sent as a bearer token on every request, and plaintext HTTP to non-local hosts is refused.
- 💾 **History** — conversations and messages persist locally (Room), with auto-generated titles, swipe-to-delete, and tap-to-continue.
- ⌨️ **Type-instead-of-speak** fallback for noisy environments.
- 🗣️ **Spoken errors** — "Connection lost", "I didn't hear anything", etc., with retry/backoff — because you're not looking at the screen.
- 🎨 **Polished UI** — Material 3 with dynamic color, dark mode, an animated central mic/status indicator, and haptics on listen start/stop.
- 🧩 **Swappable speech** — STT/TTS sit behind clean interfaces, so server-side Whisper/Piper can replace the on-device engines without touching the rest of the app.
- ⌚ **Wear OS companion** — a watch remote mirrors the phone session, sends session controls over the Wearable Data Layer, and provides a quick-launch tile. Audio and network work remain on the phone.

## Screenshots

> _Add screenshots/GIFs here once you've run the app — e.g. the home list, a live conversation with the pulsing mic indicator, and the settings screen. Drop images in `docs/` and reference them like `![Conversation](docs/conversation.png)`._

---

## Architecture

Three Gradle modules keep the portable session logic free of Android while sharing it with the Wear OS companion.

```
:core   (pure Kotlin/JVM — no Android deps)
  model/      Role, Message, Conversation
  session/    SessionPhase, SessionState, SessionCommand, SessionEvent,
              SessionController  ← the hands-free loop (state machine)
              ConversationStore, SettingsProvider  (interfaces the app implements)
  speech/     SpeechToTextEngine, TextToSpeechEngine, Transcriber, Vad (interfaces),
              UtteranceAssembler  ← owns "has the user stopped talking?"
              VadInputWindow      ← the 576-sample frame Silero requires
              SentenceBuffer
  chat/       ChatClient (interface), ChatStreamEvent, OpenAI-compatible wire DTOs,
              OpenAiStreamParser
  wear/       WearContract  ← Data Layer paths + (de)serialization shared by phone/watch
  util/       DispatcherProvider, Clock

:app    (Android phone)
  data/local/      Room: entities, DAOs, ChirpDatabase
  data/repository/ ConversationRepository       (implements ConversationStore)
  data/settings/   SettingsRepository            (EncryptedSharedPreferences; implements SettingsProvider)
                   ConnectionConfigHolder        (current base URL + API key, read per request)
  network/         OpenRouterChatClient (implements ChatClient), AuthInterceptor (bearer auth + HTTPS-for-remote)
                   OpenRouterTranscriber (implements Transcriber), WavEncoder
  speech/          PipelineSpeechToText (AudioRecord → VAD → Transcriber), AndroidTextToSpeech
  speech/mic/      MicCapture (AudioRecord), SileroVad (ONNX voice-activity detection)
  audio/           AudioRouteManager (focus + Bluetooth SCO), ListeningCues (mic open/close earcons)
  service/         ConversationService (foreground), ConversationNotification
  ui/              theme, navigation, home, conversation, settings, components, permissions
  wear/            WearDataSync, PhoneWearMessageService (phone side of Data Layer bridge)
  di/              Hilt modules (bind :core interfaces → Android impls)

:wear   (Wear OS Android app)
  WearMainActivity  mirrors phone SessionState and sends SessionCommand values
  data/             WearStateRepository, WearCommandClient
  ui/               compact watch UI and theme
  tile/             quick-launch Chirp tile
```

### The loop

`SessionController` (in `:core`) is the single source of truth. It runs one long-lived coroutine:

> **listen** → transcript → **stream** from the chat backend → feed tokens to `SentenceBuffer` → **speak** each complete sentence as soon as it's ready (streaming continues while speaking) → **auto-listen** again.

Control actions (press-primary / stop / stop-speaking / submit-text / park) interrupt the loop by cancelling and relaunching it from a clean point, with intent preserved in fields — this avoids fragile self-cancellation of an in-flight turn. Interrupting a reply mid-stream persists the partial text (marked `…`) so nothing is lost. State is exposed as a `StateFlow<SessionState>`; one-off effects (haptics) as a `SharedFlow<SessionEvent>`.

### Control flow

Every entry point funnels through the **foreground service** as an action intent, so there is one path to the controller:

```
UI (ConversationViewModel)  ─┐
Notification buttons         ├─►  ConversationService (action intents)  ─►  SessionController
Wear Data Layer  ───────────┘         (audio focus + SCO + notification)
```

The `ConversationService` adds the Android concerns the pure controller shouldn't know about: audio focus, Bluetooth SCO routing (`AudioRouteManager`), the persistent notification, and publishing state to the watch. Watch commands are received by `PhoneWearMessageService` and converted to the same service actions used by the phone UI and notification.

### Networking

`OpenRouterChatClient` posts to `{base}/chat/completions` with `stream: true` and reads the SSE response **line-by-line** via OkHttp + Okio, mapping each `data:` line with the pure `OpenAiStreamParser`. When web search is enabled it adds `tools: [{"type": "openrouter:web_search"}]`, letting OpenRouter ground the reply server-side. `AuthInterceptor` attaches `Authorization: Bearer …` to **every** request when a key is configured, and refuses plaintext HTTP to non-local hosts.

`OpenRouterTranscriber` uploads each captured utterance as multipart WAV to `{base}/audio/transcriptions`, reusing the same base URL, key and OkHttp client — OpenRouter serves speech-to-text from the same account, so there is no second credential. The transcription model is chosen in Settings, from `GET /models?output_modalities=transcription`.

---

## Build & run

Requirements: **JDK 17**, the **Android SDK** (platform 35), and Android Studio (Ladybug or newer) or a local Gradle 8.11+.

> **No JDK or Gradle installed?** `scripts/chirp-test.sh` caches JDK 17 + Gradle 8.11.1 under `~/.cache/chirp-toolchain` (idempotent; only fetches what's missing) and invokes Gradle directly, so no wrapper JAR is needed. It needs only `curl`, `tar` and `python3`. Set `CHIRP_TOOLCHAIN` to change the cache location.

> **Gradle wrapper jar:** this repository ships the wrapper *config* (`gradle/wrapper/gradle-wrapper.properties`) but not the binary `gradle-wrapper.jar`. Opening the project in Android Studio generates it automatically. From the command line, run `gradle wrapper` once (with a locally installed Gradle) to create `gradlew` + the jar, then use `./gradlew` as below.

```bash
# Android Studio: File ▸ Open ▸ select this directory, let it sync, then Run ▸ app.

# No JDK/Gradle? Bootstrap path (any Gradle args are forwarded; assembleDebug still needs the Android SDK):
scripts/chirp-test.sh                 # JVM unit tests
scripts/chirp-test.sh assembleDebug   # build the debug APK

# Command line:
gradle wrapper            # one-time, if you don't already have ./gradlew + the jar
./gradlew assembleDebug   # build the debug APK
./gradlew installDebug    # build + install on a connected device/emulator

# Tests:
./gradlew :core:test                 # JVM unit tests (sentence buffer, parser, controller, utterance assembler)
./gradlew :app:connectedAndroidTest  # Room DAO instrumentation test (needs a device/emulator)
./gradlew :wear:assembleDebug        # build the Wear OS companion APK
```

`local.properties` (pointing `sdk.dir` at your Android SDK) is created automatically by Android Studio; create it manually for CLI builds if needed.

First launch:

1. Open **Settings** (gear icon).
2. Paste your **API key** (create one at [openrouter.ai/keys](https://openrouter.ai/keys)).
3. Tap **Test connection**, then pick a **Model** (the list is fetched from `GET /models`). Toggle **Web search** if you want grounded replies.
   Optionally set a **Transcription model** under *Speech* — it defaults to `openai/whisper-1`.
4. Go back, tap **New conversation**, then tap the mic and start talking.

---

## API access: keys, gateways and HTTPS

The default endpoint is OpenRouter's public API (`https://openrouter.ai/api/v1`), authenticated with a bearer key from [openrouter.ai/keys](https://openrouter.ai/keys). OpenRouter gives access to models from Anthropic, Google, Meta, Mistral, OpenAI and many others with per-token pricing.

**Self-hosted gateways work unchanged.** Because the app speaks the plain OpenAI-compatible wire format, pointing it at e.g. a [LiteLLM](https://docs.litellm.ai/docs/simple_proxy) proxy is just a settings change — open **Advanced** under the API key section and set a different base URL; any key you configured on the gateway goes in the same field.

**Transport rules:** the app refuses to send your key over plaintext HTTP to non-local hosts (enforced in code by `AuthInterceptor`), so remote endpoints must be HTTPS.

> **Local development:** plaintext HTTP is permitted for local/private hosts (localhost, 10.x, 192.168.x, 172.16–31.x, `.local`, …), so you can point the app at an unencrypted gateway on your LAN while developing.

---

## In-app configuration

All settings persist in `EncryptedSharedPreferences` (so the API key is encrypted at rest):

| Setting | Notes |
|---|---|
| API key | Sent as `Authorization: Bearer` on every request |
| API base URL *(Advanced)* | Defaults to `https://openrouter.ai/api/v1`; any OpenAI-compatible endpoint |
| Model | Searchable picker, fetched from `GET /models`; refreshable |
| Web search | Server-side grounding via OpenRouter's `openrouter:web_search` tool (extra cost per search) |
| System prompt | Sent as the leading `system` message |
| Speaking speed | TTS rate, 0.5×–2.0× |
| Voice | Installed `TextToSpeech` voices |
| Auto-listen | Toggle the hands-free loop vs. tap-to-talk |
| Listening silence timeout | Advisory STT end-of-speech silence |

---

## Permissions

Requested at runtime, when first needed:

- `RECORD_AUDIO` — required to record the microphone for speech-to-text (requested before the first listen).
- `POST_NOTIFICATIONS` — for the ongoing session notification (requested at startup, Android 13+).
- `BLUETOOTH_CONNECT` — requested alongside the mic (Android 12+) for SCO routing.

Declared (no runtime prompt): `INTERNET`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.

---

## Testing

- **`:core` unit tests** (pure JVM, fast):
  - `SentenceBufferTest` — sentence boundary detection: confirmed terminators, decimals (`3.14`), abbreviations/initials/acronyms, newlines, and the max-length flush.
   - `OpenAiStreamParserTest` — token / usage / `[DONE]` / error / blank / unknown-field SSE lines.
  - `SessionControllerTest` — full turn via injected text (asserts persistence + sentence-by-sentence speech), stop→idle, and stream-failure→retry→"Connection lost" without persisting an empty reply. Uses fakes + a test dispatcher.
- **`:app` instrumentation test**: `ConversationDaoTest` — insert/read, ordering, user-message count, and `ON DELETE CASCADE`.

```bash
./gradlew :core:test
./gradlew :app:connectedAndroidTest
```

---

## Wear OS companion

The watch app is a thin remote, not a second voice pipeline. It renders the phone's `SessionState`, sends `SessionCommand`s, and provides a static quick-launch tile. The phone remains responsible for the microphone, Bluetooth audio, speech-to-text, text-to-speech, chat requests, and foreground service.

- `:core/wear/WearContract.kt` defines the **Data Layer** paths (`/chirp/state`, `/chirp/command`), the phone capability name, and serialization of state/commands. The phone and `:wear` both depend on `:core`.
- `app/wear/WearDataSync` publishes state and the phone's start-listening preference. `PhoneWearMessageService` receives watch commands and maps them to `ConversationService` actions, preserving the single control funnel.
- `wear/WearMainActivity` observes the synced state and sends commands through `WearCommandClient`. `ChirpTileService` opens the watch app and can start a new phone conversation.

Pair the watch with the phone and install both APKs signed with the same key. The watch must have a reachable phone advertising the `chirp_phone_session` capability; it does not need microphone or network access of its own.

---

## Known limitations

- **Bluetooth audio uses SCO for the whole session** (not A2DP). SCO is mono/narrowband, so TTS quality over Bluetooth is "phone-call" grade rather than music-grade. This is the trade-off for using the headset microphone hands-free; per-turn SCO toggling would improve playback quality at the cost of ~1–2s of latency each turn. TTS routing falls back to loud media output when no SCO headset is present.
- **Speech-to-text needs network.** Chirp records the mic itself and sends each utterance to the configured `/audio/transcriptions` endpoint, so STT does not work offline. This replaced the platform `SpeechRecognizer`, which played an unsuppressable beep on every listening session and would not honour the configured silence window — see `docs/speech-recognizer-beep-investigation.md`. Transcription adds a short pause after you stop speaking, and is billed per second on the same account as chat. The `Transcriber` interface exists so an on-device model can replace it. Chirp now plays its *own* short earcons (`ListeningCues`) as the mic opens and closes — deliberately, around the recorder rather than into it, so hands-free users can hear when it is recording.
- **Mid-stream network drops are not resumed**: retries with backoff happen only before any tokens arrive (the chat APIs can't resume a partial generation, and re-requesting would duplicate already-spoken text). After tokens start, a drop ends the turn with a spoken "Connection lost" and keeps whatever was received.
- **Web search is server-side** and billed per search by OpenRouter; generic OpenAI-compatible gateways may not support the `openrouter:web_search` tool, so disable the toggle when pointing at one.
- **Sentence splitting is heuristic.** It handles decimals, common abbreviations, initials and dotted acronyms, but unusual punctuation may split imperfectly; a long unpunctuated stream is flushed at word boundaries so speech never stalls.
- **`fallbackToDestructiveMigration()`** is used for the v1 Room database — fine for a single-version app, but add real migrations before shipping schema changes.
- **The Gradle wrapper jar is not committed** (see [Build & run](#build--run)); Android Studio or `gradle wrapper` generates it. GitHub Actions runs the JVM tests and builds both phone and Wear APKs on pull requests; version tags additionally produce signed release APKs.

---

## Project status

The phone app and Wear OS companion are implemented end to end. The watch is intentionally limited to remote controls and status display; all voice processing remains on the phone. This is a personal/self-hosted project; contributions and issues are welcome.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, conventions, and the PR checklist, and [AGENTS.md](AGENTS.md) for the architecture invariants and gotchas. In short: keep `:core` Android-free, route session control through the service, and run `./gradlew :core:test` before opening a PR.

## License

[MIT](LICENSE) © 2026 dasos.
