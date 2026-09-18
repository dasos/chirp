# Chirp — Listening Timeout Design

How Chirp decides "the user has stopped talking, transcribe what they said."
This lives in its own doc because it's a speech-recognition design question
(what counts as silence, and why), not a notification/session-state one — see
[`notification-lifecycle.md`](notification-lifecycle.md) for how a listening
window's end is *surfaced* (the standby prompt, the FGS teardown, etc.).

## Why the app owns the microphone

Chirp does not use `android.speech.SpeechRecognizer`. It records the mic itself.

The platform recognizer accepted silence-timeout extras
(`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` and friends) but treated
them as **advisory**, frequently ignoring them and finalizing a session at its
own internal endpoint mid-turn. Two symptoms followed at different points in
this app's history:

- listening cut off *before* the user finished talking, regardless of the
  configured slider value;
- listening running well past the configured timeout when sessions were
  silently restarted without a reliable ceiling.

The app worked around this by restarting the recognizer within a turn and
stitching the segments together. That worked, but every restart triggered the
recognition service's unsuppressable earcon, so a single turn beeped repeatedly
— see [`speech-recognizer-beep-investigation.md`](speech-recognizer-beep-investigation.md).

Owning the microphone resolves both problems at once, and the timeout is now
enforced exactly rather than approximated.

## Two layers

1. **Primary — `UtteranceAssembler` (`:core/speech`) + `PipelineSpeechToText`
   (`:app`).** `MicCapture` delivers fixed 512-sample frames (32ms at 16kHz);
   `SileroVad` classifies each as speech or not; `UtteranceAssembler` turns that
   stream of verdicts into a decision. (The frame the *model* sees is 576 samples —
   512 new plus 64 carried from the previous frame by `VadInputWindow`. The 512 is
   the unit of time here; don't conflate the two.) The turn ends exactly
   `silenceTimeoutMs` after the last speech frame — no polling, no restarts, no
   stitching. `UtteranceAssembler` also:
   - keeps a short **pre-roll** of frames from before speech was confirmed, so
     the leading syllable is not clipped by the VAD's reaction time;
   - ignores utterances below a **minimum speech duration** (250ms), so a cough
     or a car door is not a turn;
   - caps an utterance at **60s**, deliberately under layer 2's ceiling so
     capture always ends on its own terms.

   See `UtteranceAssemblerTest` for the deadline, pre-roll and rejection
   semantics in isolation.

2. **Last resort — `ConversationService`'s `LISTENING_SILENCE_TIMEOUT_MS`**
   (90s, `:app`). A fixed ceiling anchored to *entering* the `LISTENING` phase —
   not resettable by anything, including detected speech. It exists purely in
   case the primary layer doesn't fire. It deliberately does **not** share the
   primary layer's per-utterance resets; a resettable last-resort timer would
   defeat the point of having one. It does not fire while
   `SessionState.transcribing` is true, because at that point the mic is already
   closed and the user is waiting on the network rather than sitting in silence.

## What "silence"/"voice" actually means

Silence = **no VAD-detected speech** for `silenceTimeoutMs`.

Raw microphone amplitude is deliberately **excluded** from that decision. An
early version of this code reset the window on any RMS reading above a fixed dB
floor — a bug, not a feature: loud wind or traffic has a high RMS and is not
speech, and quiet, close-mic'd speech can have a low one. Amplitude alone cannot
distinguish the two.

This constraint outlived the recognizer. `MicCapture.rmsDb()` exists only to
drive the UI's mic pulse, and must never feed `UtteranceAssembler`. Speech
detection is Silero VAD's job, and only its verdict counts.

## Known limitation (accepted tradeoff)

A neural VAD is far better than an amplitude gate at rejecting wind and traffic,
but it is not perfect: sufficiently speech-like background noise (a nearby
conversation, a radio, a podcast in the next room) will be classified as speech
and extend the window. This is a real, understood tradeoff.

It is also why layer 2 exists and must stay non-resettable: it guarantees that
listening ends within 90s no matter how confused the VAD gets.

The two numbers are deliberately spaced: 60s of capture plus transcription time
fits inside the 90s ceiling, so the ceiling only ever fires when the pipeline
has actually failed — never on someone simply talking for a long time. Raising
the utterance cap means raising the ceiling with it.

Thresholds are in `SileroVad` — separate enter (0.5) and exit (0.35) scores, so
a probability hovering at the boundary does not chatter between speech and
silence mid-word. Tuning these is the main lever if detection misbehaves in a
particular environment; `Vad.lastProbability` is logged per frame so you can see
what you are tuning against. If detection looks *uniformly* broken rather than
mistuned, suspect the feeding, not the thresholds — see
[`stt-vad-investigation-2026-09-17.md`](stt-vad-investigation-2026-09-17.md).

## Where it's configured

Settings → Conversation → **"Listening silence timeout"** slider (1-5s) —
`AppSettings.listeningTimeoutMs` → `SessionSettings.listeningTimeoutMs` →
`SttConfig.silenceTimeoutMs` → `UtteranceAssembler.silenceTimeoutMs`. Unlike the
recognizer era, this value is now honoured exactly.

There is no separate setting for the layer-2 ceiling; it is a fixed 90s
(`ConversationService.LISTENING_SILENCE_TIMEOUT_MS`) by design — not meant to be
tuned, only to guarantee an eventual stop.
