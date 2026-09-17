# STT pipeline investigation — "mic works, but not converted to text" (2026-09-17)

**Status: unresolved — the neural VAD (Silero) does not classify captured speech as
speech on this device. A working fix (energy-based detection) is designed but not
implemented.**

## Symptom

After the STT pipeline was switched to the app-owned microphone
(`AudioRecord` + Silero VAD + `/audio/transcriptions`), the mic clearly picked up
audio (the UI pulse moved), but no utterance was ever transcribed. Every listening
turn ended with `decision=NO_SPEECH speechMs=0` and never reached the
`OpenRouterTranscriber`.

## What was verified

### 1. Mic capture is fine

A diagnostic WAV dump (every captured turn, in `PipelineSpeechToText.dumpClip`)
was pulled from a Galaxy S24 (SM-S948B) and analyzed:

- `RIFF/WAVE`, 16 kHz mono 16-bit — correct.
- Your voice is clearly audible in the file; envelope shows a classic speech
  pattern (frames 26–89), peaks −4.7 dBFS, 2903 zero-crossings/s, only 0.17 %
  clipped samples.
- With `effects=false` (`source=6`, `VOICE_RECOGNITION`, Bluetooth off), background
  noise floor sits at ~−50 dBFS vs speech at −26…−8 dBFS — a clean >20 dB gap.

### 2. The code driving the model is fine

The same model + same feeding code was run, with **identical results**, in:

- the app on Android (ORT Android 1.22.0),
- ONNX Runtime on desktop JVM (1.22.0, x86_64),
- `onnxruntime` Python on the same WAV.

For three different `sr` input shapes (scalar, `[1]`, `[[1]]`) the outputs are
identical — the sample-rate dispatch is not the issue.

### 3. The VAD model is (byte-for-byte) the official Silero export

`app/src/main/assets/silero_vad.onnx` matches the upstream
`snakers4/silero-vad/src/silero_vad/data/silero_vad.onnx`
(SHA-256 `1A153A22…8788E3`), and is identical to the mirrors
(`onnx-community/silero-vad`), and to the repo's numerical export
`silero_vad_16k_op15.onnx` in behavior. Graph inspection (via the `onnx` python
package) shows a genuine Silero v5: `sr`-dispatch wrapper → STFT → conv encoder →
GRU decoder → `Sigmoid`/`ReduceMean`, opset 16, graph producer `spox` (community
ONNX-export pipeline).

## The puzzling fact

Everything checks out — and yet the model outputs for **all** test signals:

| Input | Model output (max, 30–156 frames) |
| --- | --- |
| The user's captured speech (audible voice, −5 dBFS) | ~0.0031 |
| Synthetic 120 Hz vowel (amp 1.0) | 0.0006 |
| Synthetic 220 Hz vowel (amp 0.8) | 0.0006 |
| FM sweep 110–180 Hz | 0.06 |
| White noise | 0.002 |
| Pure zeros (silence) | 0.000535 |

None ever crosses the 0.5 speech threshold; the app's logged
`vadProbability` values (5.53E-4 → 5.353987E-4 → …) match the "zeros" trajectory
exactly. The model does *react* to input (zeros vs. noise differ), but speech never
rises above ~0.003.

## Root-cause reading

The interaction of a *known-good* model with *known-good* audio and *known-good*
driving code, that consistently scores speech at ~0.0005, is not a driver or asset
bug we could isolate further. The likely explanations remaining:

1. The capture's spectral character (device/`VOICE_RECOGNITION` pre-processing
   that the HAL applies regardless of the app's `effects=false`) is not what
   Silero expects, or
2. the community-exported ONNX behaves differently from the reference PyTorch
   model in a way that leaves its speech responses collapsed, or
3. both.

In either case the neural-VAD path is a practical dead end on this hardware for
now.

## Interim fixes attempted

- **Sigmoid on the raw output** (wrong — output is already post-activation;
  turned the flat constant into `≈0.5001`, which falsely fired "speech" on
  silence). **Reverted.**
- **AEC/NoiseSuppressor gating** in `MicCapture` — keep effects only on the
  `VOICE_COMMUNICATION`/Bluetooth path; the `VOICE_RECOGNITION` path now runs
  clean (`effects=false`). **Kept** (harmless, arguably more correct), but did
  not fix the VAD.
- **Diagnostics added** (still in the tree): throttled per-frame
  RMS + VAD logs, VAD failure logging, upload/response logging in
  `OpenRouterTranscriber`, and full-turn WAV dumps in `PipelineSpeechToText`
  (`DUMP_WAVS` flag in the companion object).

## Recommended fix (not yet implemented)

Replace the neural VAD's end-of-speech decision with a **simple adaptive energy
gate** in the core `UtteranceAssembler`:

- background noise floor estimated from the first N frames (e.g. 10),
- speech onset when frame RMS exceeds floor + ~6 dB, hold/end on the existing
  silence timeout,
- the captured audio shows a clean >20 dB separation, so this is robust here.

Keep the Silero path behind a flag/config so a working export can be swapped in
later without touching the loop. Update `UtteranceAssemblerTest` accordingly.

## Follow-ups if the VAD is revisited

- Compare the ONNX output against the reference PyTorch model
  (`torch.hub.load("snakers4/silero-vad", "silero_vad")`) on the same WAV to
  determine whether the ONNX export itself is the problem.
- Check whether the S24 `VOICE_RECOGNITION` path subtly transforms the signal
  (frequency shaping/AGC) beyond what the app controls.
- Consider `MediaRecorder.AudioSource.UNPROCESSED` for capture.