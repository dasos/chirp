package com.chirp.speech.mic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.chirp.core.speech.Vad
import com.chirp.core.speech.VadInputWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Vad] backed by Silero (MIT) on ONNX Runtime.
 *
 * A neural VAD rather than an amplitude gate is the whole point. Chirp is used
 * while walking outdoors, and raw amplitude cannot tell a voice from wind or
 * traffic; the previous recognizer-based implementation deliberately refused to
 * treat `onRmsChanged` as voice for exactly that reason. RMS is still reported
 * to the UI for the mic pulse, but it never influences this decision.
 *
 * Two things carry from frame to frame: the model's own recurrent [state], and
 * the 64 audio samples [VadInputWindow] prepends to the next frame. Both are
 * per-utterance, so [reset] clears both.
 *
 * The ONNX session is deliberately not [java.io.Closeable]: this is a
 * `@Singleton` that lives as long as the process, and `SingletonComponent` has
 * no teardown hook to close it from, so a close method would only describe a
 * lifecycle nothing honours.
 */
@Singleton
class SileroVad @Inject constructor(
    @ApplicationContext private val context: Context,
) : Vad {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession by lazy {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        env.createSession(model, OrtSession.SessionOptions())
    }

    private val window = VadInputWindow()

    /** Silero's recurrent state (h and c): shape [2, 1, 128], carried frame to frame. */
    private var state = FloatArray(2 * 1 * STATE_WIDTH)

    /** Hysteresis: once speaking, it takes a lower score to stop being speech. */
    private var speaking = false

    override var lastProbability = 0f
        private set

    override fun reset() {
        state = FloatArray(2 * 1 * STATE_WIDTH)
        window.reset()
        speaking = false
        lastProbability = 0f
    }

    override fun isSpeech(frame: ShortArray): Boolean {
        val probability = score(window.next(frame))
        lastProbability = probability

        // Separate on/off thresholds stop a score hovering at the boundary from
        // chattering between speech and silence mid-word.
        speaking = if (speaking) probability >= EXIT_THRESHOLD else probability >= ENTER_THRESHOLD
        return speaking
    }

    /** Runs one frame, advancing [state]. [input] is [Vad.INPUT_SAMPLES] wide. */
    private fun score(input: FloatArray): Float {
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, input.size.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_WIDTH.toLong()),
        )
        // Silero dispatches internally on `sr`; it is a 0-d int64 scalar, and a
        // value other than 16000/8000 fails the run rather than degrading it.
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(Vad.SAMPLE_RATE_HZ.toLong())),
            longArrayOf(),
        )

        return try {
            session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor))
                .use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val probability = (results.output("output") as Array<FloatArray>)[0][0]
                    @Suppress("UNCHECKED_CAST")
                    val nextState = results.output("stateN") as Array<Array<FloatArray>>
                    state = flatten(nextState)
                    probability
                }
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    /** Reads an output by name; positional access would silently survive a reordered export. */
    private fun OrtSession.Result.output(name: String): Any =
        get(name).orElseThrow { IllegalStateException("VAD model has no '$name' output") }.value

    private fun flatten(nested: Array<Array<FloatArray>>): FloatArray {
        val out = FloatArray(2 * STATE_WIDTH)
        var i = 0
        for (layer in nested) {
            for (batch in layer) {
                batch.copyInto(out, i)
                i += batch.size
            }
        }
        return out
    }

    private companion object {
        const val MODEL_ASSET = "silero_vad.onnx"
        const val STATE_WIDTH = 128

        const val ENTER_THRESHOLD = 0.5f
        const val EXIT_THRESHOLD = 0.35f
    }
}
