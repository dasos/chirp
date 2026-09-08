# Chirp — Listening Timeout Design

How Chirp decides "the user has stopped talking, give up and park the mic."
This lives in its own doc because it's a speech-recognition design question
(what counts as silence, and why), not a notification/session-state one — see
[`notification-lifecycle.md`](notification-lifecycle.md) for how a listening
window's end is *surfaced* (the standby prompt, the FGS teardown, etc.).

## Why the app enforces this itself

Android's `SpeechRecognizer` accepts advisory silence-timeout extras
(`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` /
`..._POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`, set in
`AndroidSpeechToText.buildIntent()` from the user's "Listening silence
timeout" setting). These are **advisory only** — Google's recognizer is well
known to frequently ignore them. Relying on the platform to honor them
produced two symptoms at different points in this app's history:

- The recognizer cutting listening off *before* the user finished talking,
  regardless of the configured slider value.
- The opposite failure: the recognizer never finalizing at all (no
  `FinalResult`, no `Error`) when there's continuous background noise —
  common for this app's core use case of walking outdoors with a Bluetooth
  headset — leaving the mic hot indefinitely.

The fix for both is the same: don't trust the OS to enforce the timeout: the
app enforces it directly, using the on-device recognizer purely as an event
source.

## Two layers

1. **Primary — `SessionController.listenOnce()` watchdog** (`core`, so it's
   unit-tested without Robolectric/Android — see `SessionControllerTest`:
   *"listening times out and parks when the recognizer never calls back"* and
   *"spaced partial results keep listening alive; sustained silence still ends
   it"*). While collecting the `SpeechToTextEngine.listen()` flow, a sibling
   coroutine races `SessionSettings.listeningTimeoutMs` (the "Listening
   silence timeout" setting, 1-5 s, Settings → Conversation) against
   `SttEvent.PartialResult` arrivals. Any partial result resets the deadline;
   if none arrives within the window, the attempt is force-ended as a
   `SttError.SPEECH_TIMEOUT` — handled identically to a genuine recognizer
   timeout by the existing `handleNoMatch` retry/give-up logic.

2. **Last resort — `ConversationService`'s `LISTENING_SILENCE_TIMEOUT_MS`**
   (30 s, `:app`). A fixed ceiling anchored to *entering* the `LISTENING`
   phase — not resettable by anything. It exists purely in case the primary
   watchdog doesn't fire (e.g. the recognizer never actually releases the mic
   despite being told to stop). It deliberately does **not** share the
   primary layer's debounce behavior — see "known limitation" below for why
   a resettable last-resort timer would defeat the point of having one.

## What "silence" actually means

Silence = **no `SttEvent.PartialResult` event from the recognizer** for
`listeningTimeoutMs`. This is a semantic signal from the ASR pipeline, not a
measurement of the room:

- `SttEvent.RmsChanged` (raw microphone amplitude) is deliberately **excluded**
  from the activity signal. Loud wind or traffic has a high RMS and is not
  speech; quiet, close-mic'd speech can have a low one. Amplitude alone
  can't distinguish the two, so it's not used at all here.
- Any `PartialResult` event counts as activity, regardless of whether its
  text differs from the previous one. A stricter "the transcript must
  meaningfully grow" check was considered and rejected — see below.

## Known limitation (accepted tradeoff)

Android's partial-result callback carries no reliable per-word confidence
score (confidence is only ever exposed on *final* results, inconsistently
across devices/vendors), so the app has no way to tell "the recognizer heard
you" apart from "the recognizer misheard background noise as a word." If an
environment is noisy enough that the on-device ASR itself hallucinates
partial hypotheses from non-speech audio, those hallucinated partials still
count as activity and extend the listening window — the same fundamental gap
that caused the original bug, now a deliberate, understood tradeoff rather
than an accidental one.

This is why layer 2 exists and must stay non-resettable: it's the guarantee
that listening ends within 30 s no matter how confused the recognizer gets.

**Why "any partial event" rather than "transcript must grow"**: comparing
new partial text against the last one to require growth was evaluated as a
stronger activity signal, but rejected — noise-driven partials almost always
produce *different* garbage text on each callback anyway, so requiring a
literal text change resets just as often as accepting any event. It adds
complexity without meaningfully improving noise robustness, so the simpler
"any `PartialResult` = activity" rule was kept.

## Where it's configured

Settings → Conversation → **"Listening silence timeout"** slider (1-5 s) —
`AppSettings.listeningTimeoutMs` → `SessionSettings.listeningTimeoutMs`. This
single value now drives both the (still-sent, still-advisory) OS extras *and*
the app's own enforced deadline in layer 1. There is no separate setting for
the layer-2 last-resort ceiling; it is a fixed 30 s
(`ConversationService.LISTENING_SILENCE_TIMEOUT_MS`) by design — it is not
meant to be tuned by the user, only to guarantee an eventual stop.
