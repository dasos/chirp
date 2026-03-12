# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Chirp** is a native Android + Wear OS app that provides voice AI interaction via OpenAI's APIs. The phone app uses WebRTC to stream audio to OpenAI's Realtime API, and also supports a one-shot push-to-talk ("Ask") mode using REST APIs. There is no backend — the app connects directly to OpenAI using a user-supplied API key stored in EncryptedSharedPreferences.

## Build Commands

```bash
# Debug build (phone)
./gradlew assembleDebug

# Debug build (watch)
./gradlew :wear:assembleDebug

# Release build
./gradlew assembleRelease

# Run JVM unit tests (no tests exist yet)
./gradlew test
```

Development is done in Android Studio. Open the project root and let Gradle sync. Use the `app` run configuration for the phone and `wear` for the watch.

## Architecture

### Modules
- `app/` — Android phone app (Kotlin, Jetpack Compose, WebRTC)
- `wear/` — Wear OS companion (remote control + status display only; no audio processing)

### Dependency Injection
`AppContainer` (in `data/`) is a manual DI container instantiated by `ChirpApp`. All singletons (stores, clients, controllers) are created there and passed into the `MainActivity` → `MainViewModel` chain.

### Two Voice Interaction Modes

**Talk mode (Realtime/WebRTC):**
`MainScreen` → `VoiceSessionService` (foreground service) → `VoiceSessionController` → `OpenAiRealtimeClient`

`OpenAiRealtimeClient` creates a WebRTC `PeerConnection` with a local audio track, performs an SDP offer/answer handshake with OpenAI's Realtime endpoint, and receives transcript deltas via a data channel. `TranscriptStore` persists arriving messages to Room (SQLite) and triggers title generation after the session ends.

**Ask mode (one-shot REST):**
User holds the Ask button → `AskSessionController` coordinates:
1. `PushToTalkRecorder` captures mic audio to an MP4 file
2. `OpenAiAskClient.transcribe()` → `/v1/audio/transcriptions` (Whisper)
3. `OpenAiAskClient.generateReply()` → `/v1/responses` (GPT)
4. `OpenAiAskClient.synthesize()` → `/v1/audio/speech` (TTS)
5. `SpeechPlayer` plays the audio response

### State Management
All state is exposed as `StateFlow` and collected in Compose with `collectAsStateWithLifecycle()`:
- `VoiceSessionController.sessionState` — WebRTC connection status
- `AskSessionController.askState` — push-to-talk pipeline status
- `TranscriptRepository` / `SessionRepository` — Room-backed transcript/session lists
- `SettingsStore` — bandwidth, speakerphone, transcription enabled, max tokens
- `ApiKeyStore` — encrypted API key (AES256)

### Wear OS
The watch is a remote control only. `WearSync` (phone side) pushes `SessionState` + latest transcript line to the watch via the Wearable Data Layer (`MessageClient`). The watch sends start/stop commands back via `WearMessageService`.

### Key Architectural Notes
- Single `Activity` with Jetpack Compose UI (`MainScreen.kt`)
- `VoiceSessionService` is a foreground service required to keep the session alive when the app backgrounds
- Room DB (`chirp_transcripts.db`) version 3 with `fallbackToDestructiveMigration()` — schema changes will wipe data
- Session titles are auto-generated via an OpenAI API call after a session ends (`OpenAiSessionTitleGenerator`)

## Git Workflow
After every meaningful change, commit with a concise imperative message:
```bash
git add <specific files>
git commit -m "Fix audio routing to use USAGE_MEDIA for speakerphone"
```
Scope one logical change per commit. Do not use `git add -A` or `git add .`.

## Coding Style
- Kotlin only; 4-space indentation; 100–120 char line limit
- Classes `CamelCase`, functions `lowerCamelCase`, packages lowercase
- All I/O uses `suspend` functions with `Dispatchers.IO`; UI state on main thread
- Keep UI logic in Compose composables, business logic in dedicated controller/client classes

## Testing
No test framework is configured yet. If adding tests, use AndroidX + JUnit: `androidTest/` for UI/instrumented tests, `test/` for JVM logic.

## Security
- Never log or hardcode the OpenAI API key
- `ApiKeyStore` uses `EncryptedSharedPreferences` with AES256_SIV + AES256_GCM
- Request microphone permission at runtime; avoid background mic usage outside an active session
