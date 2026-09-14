# Speech Recognizer End-of-Listening Beep Investigation

## Summary

Chirp does not generate a listening-end beep itself. The likely source is the Android speech-recognition service, commonly the Google or OEM recognizer, when it ends or restarts a recognition session. Android exposes no supported public setting for disabling that cue.

This is separate from TTS. Android `TextToSpeech.speak()` does not automatically insert a start or end beep, and Chirp does not call `playEarcon()` or add an earcon.

## Chirp's recognition flow

`app/src/main/kotlin/com/chirp/speech/AndroidSpeechToText.kt` uses `SpeechRecognizer`:

1. A recognizer is created and started at lines 206–210.
2. The recognizer may end a session at its own internal endpoint, regardless of the requested silence timeout.
3. `onResults()` stores the recognized segment and calls `restart()` if the app-owned turn window has not expired, at lines 191–200.
4. Normal endpoint errors (`ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT`) also call `restart()`, at lines 156–169.
5. `restart()` calls `startListening()` again, at lines 98–103.
6. `onEndOfSpeech()` only forwards an event at lines 152–154; it does not play audio.
7. Once the app-owned silence window expires, the watchdog calls `stopListening()` at lines 121–126.

The repeated recognition sessions are intentional: Android's recognizer ends too quickly, so Chirp stitches multiple finalized segments into one turn. This creates multiple opportunities for a recognizer-provided end/start cue.

## Relevant Android behavior

The public `SpeechRecognizer` API documents recognition callbacks and endpoint timing, but does not provide a mute-beep or suppress-sound option. The recognition intent's silence timing extras are advisory and do not control audio cues:

- `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`
- `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`
- `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`

AOSP's `SpeechRecognizer` source describes `startListening()` and `stopListening()`, but contains no public endpoint-sound suppression parameter. The actual audio behavior belongs to the selected recognition service, which may vary by Android version, device manufacturer, recognizer engine, audio route, and Bluetooth state.

References:

- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/speech/SpeechRecognizer.java
- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/reference/android/content/Intent#EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS

## TTS distinction

The TTS implementation is `app/src/main/kotlin/com/chirp/speech/AndroidTextToSpeech.kt`:

- It calls `TextToSpeech.speak()` at lines 91–110.
- It configures audio routing with `setAudioAttributes()` at lines 68–77 and 154–174.
- It does not call `playEarcon()`, `addEarcon()`, `playSilence()`, or any sound playback API.
- The controller does explicitly speak `"Thinking..."` before a response at `core/src/main/kotlin/com/chirp/core/session/SessionController.kt:373`, but that is speech, not a beep.

## What can be done

### Low-risk investigation

Add temporary timestamped diagnostics around:

- `onEndOfSpeech()`
- `onResults()`
- `onError()`
- `restart()` and its `startListening()` call
- the watchdog's `stopListening()` call

Then test whether the beep occurs immediately after `onResults()`, after `onError()`, after `stopListening()`, or only when a new `startListening()` begins. This identifies whether the device treats it as an end cue, a restart cue, or both.

### Low-risk product changes

Keep the stitching logic, but reduce unnecessary restarts where possible. For example, investigate whether a recognized result can remain in the current session until the turn window expires on the affected device. This may reduce beeps, but could reduce responsiveness or lose audio because the platform recognizer has already ended its session.

A different recognition engine or on-device recognizer may have different sound behavior. `SpeechRecognizer.createOnDeviceSpeechRecognizer()` is available only on supported devices and still does not guarantee silent endpointing.

### Fragile workaround

Some Android implementations have historically played recognition cues on the media stream. A workaround can temporarily lower or mute that stream around recognizer transitions, or mask the cue with silence. This is not a supported API and can:

- mute the user's music or other media;
- interact badly with Bluetooth SCO and Chirp's audio routing;
- behave differently across Android versions and OEMs;
- race with the recognizer's cue timing;
- be unacceptable for accessibility or notification audio.

It should only be an opt-in, device-tested fallback if the diagnostic confirms the cue and the user considers it disruptive enough.

### Deterministic long-term option

Own the microphone pipeline with `AudioRecord` plus voice activity detection, then send controlled audio windows to an ASR engine such as on-device Whisper or a server endpoint. This removes dependence on `SpeechRecognizer` endpoint sounds and timing, but is a substantial architectural change: it adds model/runtime cost, battery use, audio buffering, and loses some of the current platform recognizer behavior.

## Recommended conclusion

There is no reliable configuration switch to turn off the listening-end beep while retaining Android's current `SpeechRecognizer` implementation. First confirm the exact callback boundary on the target device. If confirmed as a recognizer cue, preserve the current stitching behavior and avoid a global audio mute; consider an opt-in, narrowly scoped workaround only after testing Bluetooth and media playback.
