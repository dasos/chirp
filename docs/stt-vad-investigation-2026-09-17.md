# STT pipeline investigation — "mic works, but not converted to text" (2026-09-17)

**Status: resolved 2026-09-18 — the app was feeding Silero a 512-sample tensor
where the model requires 576 (64 samples of the previous frame, then the 512 new
ones). Fixed by `VadInputWindow`; the neural VAD works.**

## Symptom

After the STT pipeline was switched to the app-owned microphone
(`AudioRecord` + Silero VAD + `/audio/transcriptions`), the mic clearly picked up
audio (the UI pulse moved), but no utterance was ever transcribed. Every listening
turn ended with `decision=NO_SPEECH speechMs=0` and never reached the
`OpenRouterTranscriber`.

## Root cause

Silero v5's STFT is a convolution (filter length 256, hop 128) that reflection-pads
only on its **right** — the `stft/padding/Pad` constant in the 16 kHz branch of the
graph is `(0, 64)`. The 64 samples of left-hand overlap are therefore the caller's
to supply. The upstream reference driver (`snakers4/silero-vad`,
`src/silero_vad/utils_vad.py`, class `OnnxWrapper`) does exactly that:

```python
num_samples  = 512   # at 16 kHz
context_size = 64
x = torch.cat([self._context, x], dim=1)      # the 'input' tensor is 576 samples
...
self._context = x[..., -context_size:]        # carried frame to frame
```

`SileroVad` passed the bare 512-sample frame. The graph's input dimension is
symbolic, so ONNX Runtime accepted it without complaint and returned a plausible
number computed from three of the four intended STFT frames, on a grid shifted 64
samples from the one the network was trained on, with the window spanning the
frame boundary missing entirely.

That silent near-miss is the whole bug. Everything the original investigation
checked really was fine: the capture, the asset, the state handling, the int16→float
scaling, the sample-rate dispatch.

## The measurement that settles it

macOS `say` → `afconvert` produced 6.2 s of 16 kHz mono speech that never went near
the phone. Both rows below run **the repo's own** `app/src/main/assets/silero_vad.onnx`
in the same process over the same samples, with the same carried `state`. The only
difference is the 64 samples:

| Feeding | max | mean | frames > 0.5 |
| --- | --- | --- | --- |
| **512, no context** (what the app did) | 0.129 | 0.0020 | **0 / 194** |
| **576 = 64 context + 512** (the contract) | 0.999999 | 0.969 | **187 / 194** |
| all-zeros through the 512 path | 0.000592 | 0.000536 | 0 / 194 |

The third row is the tell. The doc's original field numbers — speech at ~0.0031 and
a logged trajectory of `5.53E-4 → 5.353987E-4 → …` — sit right on top of the
**silence floor** of the broken path (0.000592 → 0.000540 → 0.000536 → 0.000535 …).
The model was not underrating the user's voice; it was reporting silence, correctly,
about an input that no longer resembled speech.

Because this reproduces on synthetic audio the Galaxy S24 never touched, the two
root causes the original doc was still entertaining are both dead: device/HAL
pre-processing cannot shape audio it never saw, and the export cannot be broken
when the same file scores 0.999999 on the same recording.

## What the original investigation got wrong

Worth recording, because the mistakes were methodological rather than technical.

- **"The code driving the model is fine"** rested on running the same model *and the
  same feeding code* on ORT-Android, ORT-JVM and ORT-Python. Three runtimes, one
  driver: that establishes the runtimes agree, not that the driver is right. The one
  control never run was the upstream wrapper, and it was sitting in
  `pip install silero-vad` the whole time.
- **The synthetic probes carried no information.** A 120 Hz sine, a vowel-ish tone
  and an FM sweep scoring low is the *correct* output of a speech detector. Only the
  real-speech row was diagnostic, and there was only one of it.
- **Static graph inspection was mistaken for validation.** Dumping the graph
  confirmed the ops were present; it did not check that they were being fed
  correctly. Ironically the dump contained the answer — a right-only `Pad` of 64
  should prompt the question "so where does the left context come from?".
- **Two diagnostics named as "still in the tree" never existed.** `DUMP_WAVS` and
  `dumpClip` appear in no commit, so there was no WAV dump to re-examine.
- **The log that should have caught it was lying.** `PipelineSpeechToText` logged
  `vadProbability=$isSpeech`, where `isSpeech` is a `Boolean`. The probabilities
  quoted in the original write-up had to come from a separate instrumented build.
- The graph's decoder is an **LSTM** (`/decoder/rnn/LSTM`), not a GRU, so `state`
  `[2,1,128]` is `(h, c)`. And the decaying probability trajectory was an LSTM
  relaxing to its resting fixed point under featureless input — not error
  accumulating.

## The fix

`core/speech/VadInputWindow` (pure, in `:core`, unit-tested by
`VadInputWindowTest`) owns the 64-sample carry and the int16→float conversion, and
hands `SileroVad` the 576-float payload. `Vad.FRAME_SAMPLES` stays **512** — that is
the chunk the mic reads and the unit `UtteranceAssembler` measures time in; only the
tensor is wider (`Vad.INPUT_SAMPLES`). Conflating the two would silently move the
frame duration from 32 ms to 36 ms and skew every listening timeout.

Also fixed while here: the `vadProbability` log now prints the real score
(`Vad.lastProbability`); the speculative `sr` scalar-vs-`[1]` retry is gone (the
contract is a 0-d int64 scalar, and a wrong value fails the run rather than
degrading it — `sr=8000` raises rather than silently taking the 8 kHz branch);
outputs are read by name instead of position; and detection now sits behind the
`Vad` interface in `:core`.

## Rejected: the energy-gate workaround

The original write-up recommended replacing the end-of-speech decision with an
adaptive RMS gate in `UtteranceAssembler`. **Do not implement this.** It contradicts
that class's own contract and `listening-timeout.md`: amplitude cannot tell speech
from wind or traffic, which is the bug that used to hold the listening window open
forever outdoors — and Chirp's whole use case is walking outdoors. It was a
workaround for a VAD believed unfixable, and the VAD was six lines from correct.

## If the VAD ever misbehaves again

Start by reproducing the table above — synthesise a WAV with `say`, and run the
committed asset with and without the context carry. That takes minutes and
distinguishes a driver bug from an audio problem before anything else is touched.
`VadInputWindowTest` guards the framing contract; it cannot catch a wrong sample
rate or a swapped asset, which is what that script is for.
