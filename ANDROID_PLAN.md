# Chirp Android + Wear OS Native App Plan

Date: February 11, 2026

## Goals and Constraints
- Build a **native** Android app (no WebView) and a **companion Wear OS app**.
- **Rewrite UI** using **Material Design 3 (Material You)**; do not keep current PWA UI.
- **No backend** in v1; app should be **self-contained**.
- App **prompts user for an OpenAI API key** and uses it to authenticate directly with OpenAI.
- You have **no Android experience** and **Android Studio is installed elsewhere**.
- This document is a **plan only**; no code changes in this repo yet.

## Current System Snapshot (for context)
- Backend: Flask app in `app/` with `/session` endpoint in `app/api.py` (will not be used in v1).
- Frontend: PWA in `app/templates/realtime.html` using WebRTC directly to OpenAI Realtime.
- PWA uses service worker/manifest, but those will be replaced by native apps.

## Proposed Architecture (High-Level)

### 1) Android Phone App (primary)
- **Native Kotlin + Jetpack Compose** UI.
- **WebRTC client** to OpenAI Realtime (audio in/out), using **user-provided API key**.
- Local audio capture, playback, and transcript rendering.
- Settings for bandwidth/transcription/max output tokens (replacing the PWA controls).

### 2) Wear OS Companion App
Two possible models; pick one during discovery (see “Open Questions”):
- **Remote control model (recommended for v1):**
  - Watch UI controls the phone session.
  - Audio capture and network session handled by phone.
  - Watch receives transcript snippets and state updates.
- **Standalone model (later):**
  - Watch handles its own audio/WebRTC connection.
  - Higher complexity, battery impact, and connectivity risk.

### 3) API Key Handling (No Backend)
- App prompts for and stores the OpenAI API key on device.
- Use Android Keystore + encrypted storage to protect the key at rest.
- No `/session` endpoint in v1; the app creates Realtime sessions directly with OpenAI.
- Provide a **Clear Key** action and explain that the key is user-owned.

## Impact Overview (Time, Complexity, Risk)
- **Android native + Wear OS** is a **moderate to high** effort (new tech stack, UI rewrite, audio/WebRTC).
- **WebRTC on Android** requires careful audio, lifecycle, and network handling.
- **Wear OS** adds separate UI/UX, power constraints, and connectivity flows.
- Security work is required: **protect user API key** and handle loss/rotation.

## Detailed Plan

### Phase 0: Discovery & Decisions (1–2 days)
- Define **feature scope** for v1:
  - Start/stop conversation, see transcript, basic settings, error states.
  - Confirm watch is **remote-only** for v1.
- Confirm **no backend** approach and key storage UX.
- Decide **target API levels** (aligned with Android 15 / Wear OS 6 devices):
  - Proposed: **Android phone min SDK 31 (Android 12)**.
  - Proposed: **Wear OS min SDK 33 (Android 13 / Wear OS 4)**.

### Phase 1: Android App Foundations (2–4 days)
- Create a new Android project:
  - Kotlin, Jetpack Compose, Material 3, single-activity.
  - Modules: `app` (phone) and later `wear` (Wear OS).
- Core libraries (planned):
  - **Compose Material 3**, Navigation, Lifecycle
  - **OkHttp** (REST calls to `/session`)
  - **WebRTC** (libwebrtc or official artifacts)
  - **Coroutines + Flow** for state
  - **Hilt** for DI (optional but helpful)

### Phase 2: Audio + Realtime WebRTC (Phone) (4–7 days)
- Implement session creation:
  - Phone app uses **OpenAI API key** to create Realtime sessions directly.
- Implement WebRTC flow:
  - Create `PeerConnection`, local audio track (microphone), remote audio playback.
  - Send `session.update` over data channel with settings (bandwidth/transcription/max output tokens).
  - Post SDP offer to `https://api.openai.com/v1/realtime?model=...` with user API key.
- Handle lifecycle:
  - Foreground service for active session to survive backgrounding.
  - Audio focus + mic permission flow.
  - Retry/reconnect behavior.

### Phase 3: Compose UI (Phone) — Material 3 (3–6 days)
- Redesign UI to Material 3:
  - Single primary screen: status, main action button, transcript list.
  - Secondary sheet for settings (bandwidth, transcription, max tokens).
  - Use dynamic color and typography per Material You.
- Build transcript rendering as streaming list with partial updates.
- Error states and connectivity indicators.

### Phase 4: Wear OS Companion (3–6 days)
- **Recommended v1:** watch controls the phone session.
- Implement Wear OS UI (Compose for Wear OS):
  - Start/stop button, short status, transcript snippet preview.
  - Settings toggles if needed (limited).
- Connect watch ↔ phone:
  - Use **Wearable Data Layer** (MessageClient/DataClient) for commands and status.
  - Phone sends transcript deltas and state updates to watch.

### Phase 5: Security and API Key Strategy (2–5 days)
- Prompt for API key on first launch; store in **EncryptedSharedPreferences** or equivalent.
- Offer **Key management UI**: view masked status, replace key, clear key.
- Add **local safeguards**: block when key missing, validate key format client-side.
- Document security trade-offs: no backend means key is present on device (risk of extraction).

### Phase 6: Testing & QA (3–5 days)
- Device matrix:
  - Android phone (1–2 devices/emulators) + Wear OS emulator/device.
- Test scenarios:
  - App start, mic permission, connect/disconnect, background/foreground transitions.
  - Poor network conditions, token expiry, session errors.
- Performance and battery profiling (especially for Wear).

### Phase 7: Release Prep (2–4 days)
- App signing and keystore setup.
- Play Store listing (phone + Wear OS).
- Privacy policy & permissions disclosure (microphone, network).
- Add server-side monitoring and alerting.

## Data Flow Summary (No Backend)
1) Phone app prompts user for OpenAI API key and stores it securely.
2) Phone connects directly to OpenAI Realtime with WebRTC using that key.
3) Watch communicates with phone only (v1), not with OpenAI.

## Open Questions / Decisions Needed
- None. Current decisions:
  - Transcripts stored on-device with **delete all** and **per-item delete**.
  - No preference on storage format (Room recommended for simplicity and querying).
  - **Single API key** supported at a time.

## Risks and Mitigations
- **WebRTC complexity:** use proven libraries, start with audio-only, build robust logging.
- **Battery usage:** keep sessions short, use voice activity detection, limit background time.
- **Security:** user API key stored on device; mitigate with encrypted storage and clear-key UX.
- **Wear OS constraints:** keep UI minimal, avoid always-on mic.

## Recommended Next Steps (When You’re Ready)
1) Confirm transcript storage/deletion behavior and key management UX.
2) Set up the Android project locally (Android Studio) and scaffold modules.
3) Implement phone WebRTC flow, then the Material 3 UI, then Wear OS companion.

## Cleanup (Post-Migration)
- Remove all legacy Flask/PWA/Docker files once the Android project is in place.
