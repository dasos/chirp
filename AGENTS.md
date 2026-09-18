# AGENTS.md

Guidance for Claude Code (and humans) working in this repository. Keep this file
in sync when architecture or conventions change.

## What this is

**Chirp** — a native Android app (Kotlin + Jetpack Compose) for hands-free voice
conversations with AI models via **OpenRouter** (default) or any OpenAI-compatible
endpoint. The loop: speak → STT (app-owned mic + VAD + transcription) → stream the reply →
speak it back sentence-by-sentence → auto-listen again. Runs in a foreground service
so it survives screen-off while walking with Bluetooth headphones. No custom backend —
the app talks directly to `https://openrouter.ai/api/v1` (bearer key); the advanced
base-URL setting points it at any OpenAI-compatible gateway (e.g. self-hosted LiteLLM).

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
- `app/src/main/assets/silero_vad.onnx` (~2.3MB, MIT) is the voice-activity model,
  run via ONNX Runtime. It is committed deliberately — STT does not work without
  it. Source: <https://github.com/snakers4/silero-vad>.
- **The model consumes 512-sample frames but must be handed a 576-sample tensor**:
  its STFT pads only on the right, so the caller supplies the 64 samples of
  left-hand context from the previous frame (`core/speech/VadInputWindow`). Feeding
  the bare 512 is not an error — ONNX Runtime accepts it and scores speech as
  silence, which cost a day once already. See
  `docs/stt-vad-investigation-2026-09-17.md`.

## Module / package map

Two Gradle modules — **respect the boundary**:

- **`:core`** — *pure Kotlin/JVM, no Android dependencies.* Portable logic + the
  interfaces the app implements. Must stay Android-free so it is JVM-unit-testable
  and reusable by the future `:wear` module.
  - `model/` `session/` `speech/` `chat/` `wear/` `util/`
  - Key types: `SessionController` (the loop), `SentenceBuffer`, `OpenAiStreamParser`,
    `UtteranceAssembler`, `VadInputWindow`, the `SpeechToTextEngine`/`TextToSpeechEngine`/
    `Transcriber`/`Vad`/`ChatClient`/`ConversationStore`/`SettingsProvider` **interfaces**,
    and `WearContract` (Phase 2).
- **`:app`** — Android. Implements the `:core` interfaces and adds everything
  framework-specific: `data/` (Room + EncryptedSharedPreferences), `network/`
  (OkHttp OpenRouter/OpenAI-compatible client + transcription), `speech/`
  (app-owned mic pipeline + TextToSpeech), `audio/` (focus + Bluetooth SCO),
  `service/` (foreground service + notification), `ui/` (Compose), `di/` (Hilt).

`:app` depends on `:core`. `:core` depends on nothing Android. See the README
"Architecture" section for the annotated tree.

## Architecture invariants — do not break these

1. **The hands-free loop is one long-lived coroutine** in
   `core/session/SessionController`. Control actions (press-primary/stop/
   stop-speaking/submit-text/park) interrupt it by **cancel-and-relaunch from a
   clean point** — they never mutate an in-flight turn in place. Intent is
   preserved in `@Volatile` fields (`running` / `paused` / `injectedText` /
   `saveOnInterrupt`). Always mutate loop state and (re)launch through
   `control { ... }` (holds `loopMutex`); never poke `loopJob` directly.
   Interrupting a reply mid-stream persists the partial text (marked `…`) so
   nothing the user heard is lost.
2. **One control funnel:** UI / notification buttons /
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
   `engine.stop()`). Stop-speaking / interrupting rely on this.

## How the streaming ↔ TTS coupling works

`OpenRouterChatClient` streams `POST {base}/chat/completions` SSE line-by-line
(OkHttp + Okio) and maps each `data:` line with the pure `OpenAiStreamParser`.
Tokens feed `SentenceBuffer`, which
emits a sentence as soon as a terminator is *confirmed* by trailing whitespace; a
consumer coroutine speaks each sentence while streaming continues. **Retry-with-
backoff happens only before the first token arrives** — the chat APIs can't resume a
partial generation, so re-requesting mid-stream would duplicate already-spoken text.
When web search is enabled, the request adds OpenRouter's `openrouter:web_search`
server tool; search runs server-side and never interrupts the token stream.

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
- Secrets (the API key) live only in `SettingsRepository`
  (EncryptedSharedPreferences) — never plain prefs, never logs.
- Versions in `gradle/libs.versions.toml` are a **compatible set** (Kotlin / KSP /
  Compose-compiler / AGP / Hilt / Room). Bump them together, not piecemeal.

## Non-obvious constraints / gotchas

- The foreground service is `foregroundServiceType="microphone"`. On **Android 14+**
  starting it requires `RECORD_AUDIO` to be granted. **Every** session start (mic
  tap *and* type-while-idle) is gated on the mic permission in `ConversationScreen`
  — keep any new start path gated too.
- **STT does not use `android.speech.SpeechRecognizer`.** Chirp owns the mic:
  `AudioRecord` → Silero VAD → an `/audio/transcriptions` call
  (`app/speech/PipelineSpeechToText` + `app/speech/mic/`). Detection sits behind the
  `core/speech/Vad` interface; swapping detectors is one `@Binds` line in
  `BindingsModule`. The platform
  recognizer plays an unsuppressable earcon on every `startListening()` and
  would not honour the configured silence window; see
  `docs/speech-recognizer-beep-investigation.md` for the evidence before
  proposing a return to it.
- `core/speech/UtteranceAssembler` owns the "has the user stopped talking?"
  decision and enforces the "Listening silence timeout" setting exactly, with a
  fixed 90 s non-resettable ceiling in `ConversationService` as a last resort.
  Only VAD-detected speech counts — never raw mic amplitude, which can't tell
  speech from wind or traffic. RMS is reported to the UI for the mic pulse and
  must never feed the silence decision. See `docs/listening-timeout.md` before
  touching any of this.
- Bluetooth: SCO stays on for the whole session (mic-routing priority). TTS routing
  switches between `USAGE_VOICE_COMMUNICATION` (SCO) and `USAGE_MEDIA` (no headset)
  via `AudioRouteManager.isBluetoothHeadsetConnected()` →
  `AndroidTextToSpeech.applyCommunicationRouting`. The system can drop SCO at
  screen-off; `AudioRouteManager.reassertCommunicationRoute()` re-applies the voice
  link (the service calls it at each LISTENING/SPEAKING phase boundary).
- HTTPS for non-local hosts is enforced in **code** (`network/AuthInterceptor`), not
  just the manifest. `res/xml/network_security_config.xml` permits cleartext as a
  base config for local dev; the real enforcement is the interceptor.
- EncryptedSharedPreferences keys are device-bound → excluded from backup
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Don't add secrets to backup.
- Room uses `fallbackToDestructiveMigration()` (schema v1). Add real `Migration`s and
  drop the destructive fallback before changing the schema in a shipped build.

## Tests to keep green

- `core/src/test`: `SentenceBufferTest`, `OpenAiStreamParserTest`,
  `SessionControllerTest` (fakes + `StandardTestDispatcher`).
- `app/src/androidTest`: `ConversationDaoTest`.

When you change the loop, the sentence buffer, or the parser, update/extend these.
CI (`.github/workflows/ci.yml`) runs on **pull requests and `v*` tags** — not on
pushes to `main`. PRs run `:core:test` + `assembleDebug` as a compile check; a tag
additionally builds signed, minified **release** APKs, verifies the VAD model and
ONNX native library survived R8, and publishes them to a GitHub Release.

## Releases

CI triggers only on `v*` tags. To make a release, tag the **specific commit** you want to ship **only when you're ready** — not after every merge:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

This kicks off CI, which builds the **signed release** APKs (phone + wear) and auto-creates a GitHub Release with them attached + generated release notes. CI enforces that the tag name matches `versionName` in `app/build.gradle.kts` — bump the version before tagging. Do **not** tag for workflow-only changes (e.g. CI config) — those can be committed to `main` without triggering a build.

The tag build is the **only** thing that runs R8 over the app, so a release is
also the first place a missing ProGuard keep rule can surface. CI guards the two
that would otherwise fail silently on-device — the Silero VAD model asset and the
ONNX Runtime native library — but a new reflective dependency needs its own rule
in `app/proguard-rules.pro` (or `wear/proguard-rules.pro`) and its own check here.

Both modules sign with the committed `app/signing/debug.keystore`. That is
deliberate: distribution is GitHub Releases rather than Play, and the phone and
watch APKs must share a signer for Wear Data Layer pairing. It does mean the
signing key is public, so it is not suitable for Play Store distribution — that
would need a real upload key injected from repository secrets.

## Commits

Commit frequently. Offer to push when appropriate.

## Phase 2 (Wear OS) — architected, not built

The watch is a thin remote that renders `SessionState` and sends `SessionCommand`s.
The shared wire format is `core/wear/WearContract` (Data Layer paths +
serialization). Integration points are marked `// PHASE 2`. To add it: create a
`:wear` module (uncomment the include in `settings.gradle.kts`), depend on `:core`,
add a phone-side `WearableListenerService` that publishes `SessionController.state`
to `/chirp/state` and forwards `/chirp/command` messages into `ConversationService`
actions, and a Wear Compose UI mirroring `SessionState`.

# Development style

You are a lazy senior developer. Lazy means efficient, not careless. The best code is the code never written.

Before writing any code, stop at the first rung that holds:

1. Does this need to be built at all? (YAGNI)
2. Does it already exist in this codebase? Reuse the helper, util, or pattern that's already here, don't re-write it.
3. Does the standard library already do this? Use it.
4. Does a native platform feature cover it? Use it.
5. Does an already-installed dependency solve it? Use it.
6. Can this be one line? Make it one line.
7. Only then: write the minimum code that works.

The ladder runs after you understand the problem, not instead of it: read the task and the code it touches, trace the real flow end to end, then climb.

Bug fix = root cause, not symptom: a report names a symptom. Grep every caller of the function you touch and fix the shared function once — one guard there is a smaller diff than one per caller, and patching only the path the ticket names leaves a sibling caller still broken.

Rules:

- No abstractions that weren't explicitly requested.
- No new dependency if it can be avoided.
- No boilerplate nobody asked for.
- Deletion over addition. Boring over clever. Fewest files possible.
- Shortest working diff wins, but only once you understand the problem. The smallest change in the wrong place isn't lazy, it's a second bug.
- Question complex requests: "Do you actually need X, or does Y cover it?"
- Pick the edge-case-correct option when two stdlib approaches are the same size, lazy means less code, not the flimsier algorithm.
- Mark deliberate simplifications that cut a real corner with a known ceiling (global lock, O(n²) scan, naive heuristic) with a `ponytail:` comment naming the ceiling and upgrade path.

Not lazy about: understanding the problem (read it fully and trace the real flow before picking a rung, a small diff you don't understand is just laziness dressed up as efficiency), input validation at trust boundaries, error handling that prevents data loss, security, accessibility, the calibration real hardware needs (the platform is never the spec ideal, a clock drifts, a sensor reads off), anything explicitly requested. Lazy code without its check is unfinished: non-trivial logic leaves ONE runnable check behind, the smallest thing that fails if the logic breaks (an assert-based demo/self-check or one small test file; no frameworks, no fixtures). Trivial one-liners need no test.

(Yes, this file also applies to agents working on the ponytail repo itself. Especially to them.)