# The recognizer beep, and why Chirp owns the microphone

**Status: resolved.** Chirp no longer uses `android.speech.SpeechRecognizer`.
This document records what the beep was, why no configuration could remove it,
and what replaced it.

## The symptom

Several beeps during a single listening turn, on a Samsung device, **both over
Bluetooth and on the loudspeaker**. Playing on the loudspeaker rules out a
Bluetooth SCO link tone, which is the other plausible source.

## What the beep actually was

Chirp never played it. There is no `playEarcon`, `addEarcon`, `ToneGenerator`,
`SoundPool`, `MediaPlayer` or `res/raw/` sound anywhere in the app.

The cue came from the recognition service. Logcat on current devices shows it
plainly:

```
I/AudioPlayer: Playing beep com.google.android.tts:raw/open (size 8626 bytes)
```

It is an 8,626-byte raw resource named `open`, inside **`com.google.android.tts`**
("Speech Recognition & Synthesis"), played by that app's own audio player the
moment `startListening()` is called — before `onReadyForSpeech`.

That location is the whole problem. `com.google.android.tts` is a
**Play-updatable app, not part of the platform**. AOSP's `SpeechRecognizer.java`
and `RecognizerIntent.java` contain nothing sound-related at all, so there is no
API to call, no permission to hold, and no Android version to wait for. Google
can also change the behaviour on any device at any time, independently of the OS
version — which is why every workaround in circulation is fragile.

## Why Chirp heard it several times per turn

The system recognizer finalized sessions at its own internal silence threshold
and largely ignored the `EXTRA_SPEECH_INPUT_*` extras, so the configured
"Listening silence timeout" could not be honoured directly. The old
implementation compensated by restarting the recognizer within a single turn and
stitching the finalized segments together.

Every restart was a fresh `startListening()`, and therefore a fresh earcon. The
mechanism that made the timeout setting work was the same mechanism that
multiplied the beeps.

## Approaches that do not work

- **No intent extra disables it.** Every `EXTRA_*` constant in `RecognizerIntent`
  was enumerated; none relate to sound. Widely-copied constants
  `android.speech.extra.DICTATE_BEEP` and `BEEP_SOUND` do not exist in AOSP — a
  GitHub-wide search for `DICTATE_BEEP` returns a single hit, in an unverified
  machine-authored PR whose own test checklist is unticked. `DICTATION_MODE` is
  real folklore but concerns listening duration, not audio.
- **`createOnDeviceSpeechRecognizer()` (API 31+) does not help.** Device logs
  show Google's on-device SODA engine initialising *and* the beep playing in the
  same window; the earcon comes from the hosting app either way.
- **Stream-volume muting is unreliable and hazardous.** The beep has moved
  between `STREAM_MUSIC`, `STREAM_SYSTEM`, `STREAM_NOTIFICATION` and
  `STREAM_RING` across versions and OEMs. Reports indicate a ~300ms pre-delay is
  needed before it takes effect, and the streams it currently uses are exactly
  the ones whose adjustment throws `SecurityException` without Do Not Disturb
  access. It also risks muting the user's music.
- **Audio focus does not suppress it.** `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`
  governs *other* apps, not the recognition service's own playback. The
  recognizer additionally grabs focus itself.

`EXTRA_SEGMENTED_SESSION` (API 33+) would have reduced the count to one earcon
per turn by keeping a single session open across pauses. It was rejected: it
does not reach silence, and it is unavailable below API 33 (Chirp's `minSdk` is
26).

## What replaced it

`app/speech/PipelineSpeechToText` — `AudioRecord` → Silero VAD → `Transcriber`.

- **`app/speech/mic/MicCapture`** opens the mic at 16kHz mono PCM16. Opening
  `AudioRecord` plays no sound.
- **`app/speech/mic/SileroVad`** classifies each 512-sample frame as speech or
  not, via ONNX Runtime. A neural VAD rather than an amplitude gate is essential:
  amplitude cannot distinguish a voice from wind or traffic, which is why the
  previous implementation deliberately refused to treat `onRmsChanged` as voice.
  RMS is still reported for the mic pulse, but never feeds the silence decision.
- **`core/speech/UtteranceAssembler`** owns the end-of-turn decision, and ends
  the turn exactly `silenceTimeoutMs` after the last speech frame. It keeps a
  short pre-roll so the leading syllable is not clipped, ignores bursts below a
  minimum speech duration, and caps an utterance at 60s.
- **`network/OpenRouterTranscriber`** uploads the clip as multipart WAV to
  `{baseUrl}/audio/transcriptions`, reusing the same base URL, bearer key and
  OkHttp client as the chat calls.

Two problems, one fix: the beep is gone because nothing in this path plays a
sound, and the silence timeout is now exact rather than approximated.

## What it cost

- **STT requires network.** There is no offline fallback.
- **Latency moved.** Transcription happens after speech ends rather than
  arriving as the user talks. `SttEvent.EndOfSpeech` marks the upload so the UI
  can show "Transcribing…" instead of looking stalled.
- **No live partial transcripts.** A batch transcriber cannot produce them. The
  `Transcriber` seam leaves room for a streaming engine, or for a small local
  model driving throwaway partials alongside an accurate final transcript.
- **Accuracy and cost are now ours.** The transcription model is configurable in
  Settings; the response reports per-request cost.

## Verifying the beep is gone

```bash
adb logcat -c && adb logcat | grep -i "playing beep"
```

Run a full conversation turn. Expect no output. Running this against a build
that still used `SpeechRecognizer` shows one line per recognizer restart, which
is what confirmed the diagnosis.
