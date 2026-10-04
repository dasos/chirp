# Android Audio Routing Investigation

## Summary

Chirp uses the Bluetooth hands-free profile for both the car microphone and
spoken output. Android exposes that path as a communication or telephone route
(HFP/SCO), rather than as ordinary Bluetooth media (A2DP). This is intentional:
the car microphone is normally available only through the hands-free route.

The route is more fragile than media playback. Android or a car head unit can
temporarily remove the SCO communication device when the screen turns off, when
the microphone is released, or while Bluetooth changes state. The Bluetooth
HEADSET profile can still report connected during that interval.

Those are different states:

- HFP profile connected: the durable indication that a headset/car is attached.
- SCO communication device available: the current route can be selected now.
- `setCommunicationDevice()` succeeded: one route-selection attempt worked.
- Audio focus retained: Chirp is still allowed to use the communication stream.

The last three can change temporarily without the first one changing.

## Failure mode

Previously, a failed route-selection attempt was also used to choose the TTS
output usage. A temporary SCO failure therefore changed TTS to
`USAGE_MEDIA`, even though HFP was still connected. That could move a car from
telephone audio back to media audio and make the HFP call-like route unstable.

TTS now keeps using `USAGE_VOICE_COMMUNICATION` while the HFP profile remains
connected. Route establishment is retried independently when the session starts,
when the screen changes state, and when Android reports communication-device
changes.

Retries are deliberately bounded. If they exhaust, the app records a warning
but does not silently change the intended HFP output mode.

## Session lifecycle

At session start, Chirp requests permanent voice-communication audio focus,
sets `MODE_IN_COMMUNICATION`, and selects a Bluetooth SCO device when Android
offers one. The foreground service reasserts the route when entering listening
or speaking.

The service still treats permanent audio-focus loss as a session park. This is
conservative: another app or the car may have taken the audio channel. Route
and focus diagnostics are needed before changing that behavior, because blindly
ignoring permanent focus loss can make Chirp fight another audio owner.

During transcription, `AudioRecord` is released before the network request. This
reduces microphone and SCO contention, but some car head units may drop HFP when
there is no active communication stream. The next listening or speaking phase,
plus the bounded route retries, is expected to restore it.

## Diagnostics

Relevant log tags are:

- `AudioRouteManager`
- `AndroidTextToSpeech`
- `ConversationService`
- `PipelineSpeechToText`
- `MicCapture`

The most useful failure sequence is:

```text
phase=SPEAKING
utterance started
focusChange=LOSS
utterance cancelled
endSession / clearCommunicationDevice
```

That indicates audio focus or HFP loss interrupted TTS. A sequence containing
`no SCO device found` or `setCommunicationDevice(...) -> false` without a
Bluetooth profile disconnect indicates a transient route failure instead.

## Limitations

Car head units vary in how long they keep HFP active when the microphone is not
being used. A normal Bluetooth headset can validate Android route recovery but
cannot fully reproduce every car's HFP policy. The remaining car-specific test
is to repeat a speaking turn with the phone screen on and off, while collecting
the logs above.
