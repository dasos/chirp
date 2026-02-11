# Chirp — Native Android + Wear OS

Chirp is a **native** Android phone app with a **Wear OS companion** that connects directly to OpenAI Realtime using WebRTC. The UI is rebuilt with **Material Design 3** and supports on-device transcript history with deletion controls.

## Key Notes (Security)
- This app **prompts for an OpenAI API key** and stores it **locally** using encrypted storage.
- OpenAI discourages using API keys inside client apps. This is a trade-off you requested.
- Use a key you control and rotate it if you suspect compromise.

## Requirements
- Android Studio (recommended).
- Android 12+ (minSdk 31) for the phone app.
- Wear OS 4+ (minSdk 33) for the watch app.
- Android 15 / Wear OS 6 devices are supported.

## Project Structure
```
app/   # Android phone app (Compose, WebRTC)
wear/  # Wear OS companion (remote control + status)
```

## Open in Android Studio
1) Open Android Studio.
2) Choose **Open** and select this repository root.
3) Let Gradle sync.

If Gradle wrapper JAR is missing, generate it from a terminal:
```bash
./gradlew wrapper
```

## Run
- Phone app: choose `app` run configuration.
- Wear app: choose `wear` run configuration (emulator or physical watch).

## Features
- Native audio session with OpenAI Realtime (WebRTC).
- Material 3 UI with status, connect/disconnect, and settings.
- Transcript history with **delete all** and **per-item delete**.
- Wear OS remote control + live status + last transcript line.

## Known Trade-offs
- No backend means the API key is present on the device (risk of extraction).
- WebRTC + audio on Android requires careful permissions and power management.

## License
MIT (see `LICENSE`).
