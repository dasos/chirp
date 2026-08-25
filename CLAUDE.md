# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository. Keep this file
in sync when architecture or conventions change.

## What this is

**Chirp** — a native Android app (Kotlin + Jetpack Compose) for hands-free voice
conversations with a self-hosted **Ollama** server. The loop: speak → on-device
STT → stream the reply from Ollama → speak it back sentence-by-sentence → auto-
listen again. Runs in a foreground service so it survives screen-off while walking
with Bluetooth headphones. No custom backend — the app talks directly to Ollama.

## Build / test / run

```bash
# The Gradle wrapper JAR is NOT committed — generate it once (or open in Android Studio):
gradle wrapper

./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build + install on a device/emulator
./gradlew :core:test                    # JVM unit tests — FAST, no Android SDK needed; run these for logic changes
./gradlew :app:connectedAndroidTest     # Room DAO instrumentation test (needs a device/emulator)
```

- Requires **JDK 17** and the **Android SDK** (platform 35). Some sandboxes have no
  Android SDK — `:core:test` only needs the JDK, so prefer it for quick checks.
- Dependency versions live in `gradle/libs.versions.toml`.

## Module / package map

Two Gradle modules — **respect the boundary**:

- **`:core`** — *pure Kotlin/JVM, no Android dependencies.* Portable logic + the
  interfaces the app implements. Must stay Android-free so it is JVM-unit-testable
  and reusable by the future `:wear` module.
  - `model/` `session/` `speech/` `chat/` `wear/` `util/`
  - Key types: `SessionController` (the loop), `SentenceBuffer`, `OllamaStreamParser`,
    the `SpeechToTextEngine`/`TextToSpeechEngine`/`ChatClient`/`ConversationStore`/
    `SettingsProvider` **interfaces**, and `WearContract` (Phase 2).
- **`:app`** — Android. Implements the `:core` interfaces and adds everything
  framework-specific: `data/` (Room + EncryptedSharedPreferences), `network/`
  (OkHttp Ollama client), `speech/` (SpeechRecognizer/TextToSpeech), `audio/`
  (focus + Bluetooth SCO + MediaSession), `service/` (foreground service +
  notification), `ui/` (Compose), `di/` (Hilt).

`:app` depends on `:core`. `:core` depends on nothing Android. See the README
"Architecture" section for the annotated tree.

## Architecture invariants — do not break these

1. **The hands-free loop is one long-lived coroutine** in
   `core/session/SessionController`. Control actions (pause/resume/stop/
   stop-speaking/submit-text) interrupt it by **cancel-and-relaunch from a clean
   point** — they never mutate an in-flight turn in place. Intent is preserved in
   `@Volatile` fields (`running` / `paused` / `injectedText`). Always mutate loop
   state and (re)launch through `control { ... }` (holds `loopMutex`); never poke
   `loopJob` directly.
2. **One control funnel:** UI / notification buttons / headset media buttons /
   (future) Wear → `ConversationService` action intents → `SessionController`. Add
   new control entry points as **service actions**, not by calling the controller
   from arbitrary places.
3. **STT/TTS/Chat are used only through the `:core` interfaces.** To add server-side
   Whisper/Piper, write a new implementation + a Hilt binding — do not touch the
   controller or UI.
4. `SessionController.state: StateFlow<SessionState>` is the **single source of
   truth** rendered by the UI, the notification, and (Phase 2) the watch. One-off
   effects (haptics) go through `events: SharedFlow<SessionEvent>`.
5. **TTS cancellation must stop playback** (`AndroidTextToSpeech.speak` cancels →
   `engine.stop()`). Pause / stop-speaking rely on this.

## How the streaming ↔ TTS coupling works

`OllamaChatClient` streams `/api/chat` NDJSON line-by-line (OkHttp + Okio) and maps
each line with the pure `OllamaStreamParser`. Tokens feed `SentenceBuffer`, which
emits a sentence as soon as a terminator is *confirmed* by trailing whitespace; a
consumer coroutine speaks each sentence while streaming continues. **Retry-with-
backoff happens only before the first token arrives** — Ollama can't resume a
partial generation, so re-requesting mid-stream would duplicate already-spoken text.

## Conventions

- Kotlin official style, 4-space indent, trailing commas.
- **Hilt** for DI: bind `:core` interfaces in `app/di/BindingsModule` (`@Binds`),
  provide framework types in `AppModule` / `DatabaseModule` (`@Provides`).
  `SessionController` is `@Inject @Singleton` in `:core` using `javax.inject`; Hilt
  constructs it because all its deps are bound in `:app`.
- Coroutines + Flow throughout. ViewModels expose `StateFlow`; UI collects with
  `collectAsStateWithLifecycle`.
- **Compose Material 3 only** — no Material 2, no AppCompat. Dynamic color + dark
  mode via `ui/theme/ChirpTheme`.
- Secrets (basic-auth password) live only in `SettingsRepository`
  (EncryptedSharedPreferences) — never plain prefs, never logs.
- Versions in `gradle/libs.versions.toml` are a **compatible set** (Kotlin / KSP /
  Compose-compiler / AGP / Hilt / Room). Bump them together, not piecemeal.

## Non-obvious constraints / gotchas

- The foreground service is `foregroundServiceType="microphone"`. On **Android 14+**
  starting it requires `RECORD_AUDIO` to be granted. **Every** session start (mic
  tap *and* type-while-idle) is gated on the mic permission in `ConversationScreen`
  — keep any new start path gated too.
- `SpeechRecognizer` must be created/driven on the **main thread**
  (`AndroidSpeechToText` posts to the main `Handler`). Don't call it off-main.
- Bluetooth: SCO stays on for the whole session (mic-routing priority). TTS routing
  switches between `USAGE_VOICE_COMMUNICATION` (SCO) and `USAGE_MEDIA` (no headset)
  via `AudioRouteManager.scoActive` → `AndroidTextToSpeech.applyCommunicationRouting`.
- HTTPS for non-local hosts is enforced in **code** (`network/AuthInterceptor`), not
  just the manifest. `res/xml/network_security_config.xml` permits cleartext as a
  base config for local dev; the real enforcement is the interceptor.
- EncryptedSharedPreferences keys are device-bound → excluded from backup
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Don't add secrets to backup.
- Room uses `fallbackToDestructiveMigration()` (schema v1). Add real `Migration`s and
  drop the destructive fallback before changing the schema in a shipped build.

## Tests to keep green

- `core/src/test`: `SentenceBufferTest`, `OllamaStreamParserTest`,
  `SessionControllerTest` (fakes + `StandardTestDispatcher`).
- `app/src/androidTest`: `ConversationDaoTest`.

When you change the loop, the sentence buffer, or the parser, update/extend these.
CI (`.github/workflows/ci.yml`) runs `:core:test` and `assembleDebug` on push/PR.

## Phase 2 (Wear OS) — architected, not built

The watch is a thin remote that renders `SessionState` and sends `SessionCommand`s.
The shared wire format is `core/wear/WearContract` (Data Layer paths +
serialization). Integration points are marked `// PHASE 2`. To add it: create a
`:wear` module (uncomment the include in `settings.gradle.kts`), depend on `:core`,
add a phone-side `WearableListenerService` that publishes `SessionController.state`
to `/chirp/state` and forwards `/chirp/command` messages into `ConversationService`
actions, and a Wear Compose UI mirroring `SessionState`.
