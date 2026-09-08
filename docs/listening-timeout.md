# Chirp — Listening Timeout Design

How Chirp decides "the user has stopped talking, give up and park the mic."
This lives in its own doc because it's a speech-recognition design question
(what counts as silence, and why), not a notification/session-state one — see
[`notification-lifecycle.md`](notification-lifecycle.md) for how a listening
window's end is *surfaced* (the standby prompt, the FGS teardown, etc.).

## Why the app enforces this itself

Android's `SpeechRecognizer` accepts advisory silence-timeout extras
(`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` /
`..._POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`, still set in
`AndroidSpeechToText.buildIntent()` from the user's "Listening silence
timeout" setting, for whatever devices do honor them). These are **advisory
only** — Google's recognizer is well known to frequently ignore them, and it
also finalizes a *session* at its own internal endpoint even mid-turn. Relying
on the platform alone produced two symptoms at different points in this app's
history:

- The recognizer cutting listening off *before* the user finished talking,
  regardless of the configured slider value.
- Listening running well past the configured timeout when recognition
  sessions kept getting silently retried/restarted without a reliable ceiling.

The fix for both is the same principle: don't trust the OS to enforce the
timeout — the app enforces it directly, using the on-device recognizer purely
as an event source (transcript + errors), never as the authority on timing.

## Two layers

1. **Primary — `SttTurnWindow` (`:core/speech`) + `AndroidSpeechToText`
   (`:app`)**. `AndroidSpeechToText.listen()` no longer maps 1:1 to a single
   recognizer session: it owns a whole "turn" that may span several
   sessions. `SttTurnWindow` tracks wall-clock time since the last detected
   voice; whenever a recognizer session ends early (`NO_MATCH` /
   `SPEECH_TIMEOUT` / an `onResults` that arrives before the window expires),
   `AndroidSpeechToText` silently restarts a fresh session and stitches its
   finalized segment onto the running transcript (`SttTurnWindow.accumulated`).
   A background watchdog coroutine also polls `SttTurnWindow.expired()` every
   200ms and force-stops the in-flight session once the deadline passes, so
   the turn ends on schedule even if the recognizer never calls back at all.
   The turn only emits `SttEvent.FinalResult` (the stitched transcript) or
   `Error(NO_MATCH)` once `SttTurnWindow.expired()` is true — see
   `SttTurnWindowTest` for the deadline/reset semantics in isolation.

2. **Last resort — `ConversationService`'s `LISTENING_SILENCE_TIMEOUT_MS`**
   (30s, `:app`). A fixed ceiling anchored to *entering* the `LISTENING`
   phase — not resettable by anything, including recognized speech. It exists
   purely in case the primary layer doesn't fire (e.g. a bug or platform
   quirk that keeps `AndroidSpeechToText`'s flow alive with no event at all).
   It deliberately does **not** share the primary layer's per-utterance
   resets — see "known limitation" below for why a resettable last-resort
   timer would defeat the point of having one.

## What "silence"/"voice" actually means

Silence = **no non-blank recognized text** (partial or final) for
`silenceTimeoutMs`. `SttTurnWindow.onVoice()` is called only from
`onPartialResults` and `onResults` in `AndroidSpeechToText` — i.e. only when
the recognizer itself believes it heard *words*, right or wrong.

`SttEvent.RmsChanged` (raw microphone amplitude) is deliberately **excluded**
from voice detection. An earlier version of this code reset the window on any
RMS reading above a fixed dB floor — that's a bug, not a feature: loud wind or
traffic has a high RMS and is not speech, and quiet, close-mic'd speech can
have a low one. Amplitude alone can't distinguish the two, so `onRmsChanged`
only forwards the event for UI purposes (waveform/pulse) and never touches the
window.

## Known limitation (accepted tradeoff)

Android's partial-result callback carries no reliable per-word confidence
score (confidence is only ever exposed on *final* results, inconsistently
across devices/vendors), so the app has no way to tell "the recognizer heard
you" apart from "the recognizer misheard background noise as a word." If an
environment is noisy enough that the on-device ASR itself hallucinates
partial hypotheses from non-speech audio, those hallucinated partials still
count as voice and extend the window — a real, understood tradeoff rather
than an accidental one.

This is why layer 2 exists and must stay non-resettable: it's the guarantee
that listening ends within 30s no matter how confused the recognizer gets.

## Where it's configured

Settings → Conversation → **"Listening silence timeout"** slider (1-5s) —
`AppSettings.listeningTimeoutMs` → `SessionSettings.listeningTimeoutMs` →
`SttConfig.silenceTimeoutMs`. This single value now drives both the
(still-sent, still-advisory) OS extras *and* `SttTurnWindow`'s enforced
deadline in layer 1. There is no separate setting for the layer-2 last-resort
ceiling; it is a fixed 30s (`ConversationService.LISTENING_SILENCE_TIMEOUT_MS`)
by design — it is not meant to be tuned by the user, only to guarantee an
eventual stop.
