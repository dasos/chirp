package com.chirp.speech.mic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silero VAD (MIT) run on ONNX Runtime — decides, per frame, whether the frame
 * contains speech.
 *
 * A neural VAD rather than an amplitude gate is the whole point. Chirp is used
 * while walking outdoors, and raw amplitude cannot tell a voice from wind or
 * traffic; the previous recognizer-based implementation deliberately refused to
 * treat `onRmsChanged` as voice for exactly that reason. RMS is still reported
 * to the UI for the mic pulse, but it never influences this decision.
 *
 * The model is stateful across frames, so one instance handles one utterance:
 * call [reset] between turns. Not thread-safe — drive it from a single capture
 * coroutine.
 */
@Singleton
class SileroVad @Inject constructor(
    @ApplicationContext private val context: Context,
) : Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession by lazy {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        env.createSession(model, OrtSession.SessionOptions())
    }

    /** Silero's recurrent state: shape [2, 1, 128], carried frame to frame. */
    private var state = FloatArray(2 * 1 * STATE_WIDTH)

    /** Hysteresis: once speaking, it takes a lower score to stop being speech. */
    private var speaking = false

    /** Whether this export wants `sr` as a scalar; see [runModel]. */
    private var srScalar = true
    private var srShapeSettled = false

    fun reset() {
        state = FloatArray(2 * 1 * STATE_WIDTH)
        speaking = false
    }

    /**
     * Returns true when [frame] contains speech. [frame] must be exactly
     * [FRAME_SAMPLES] mono PCM16 samples at [SAMPLE_RATE_HZ].
     */
    fun isSpeech(frame: ShortArray): Boolean {
        require(frame.size == FRAME_SAMPLES) {
            "Silero expects $FRAME_SAMPLES samples per frame, got ${frame.size}"
        }

        val pcm = FloatArray(FRAME_SAMPLES) { frame[it] / 32768f }

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(pcm),
            longArrayOf(1, FRAME_SAMPLES.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, STATE_WIDTH.toLong()),
        )
        val probability = try {
            runModel(inputTensor, stateTensor)
        } finally {
            inputTensor.close()
            stateTensor.close()
        }

        // Separate on/off thresholds stop a score hovering at the boundary from
        // chattering between speech and silence mid-word.
        speaking = if (speaking) probability >= EXIT_THRESHOLD else probability >= ENTER_THRESHOLD
        return speaking
    }

    /**
     * Runs one frame. Silero has shipped exports that declare the sample-rate
     * input as a scalar and others that declare it as shape `[1]`; rather than
     * guessing, the first mismatch flips the shape and retries, after which the
     * working form is reused for the life of the session.
     */
    private fun runModel(input: OnnxTensor, stateIn: OnnxTensor): Float {
        try {
            return runWithSampleRate(input, stateIn, srScalar)
        } catch (e: OrtException) {
            if (srShapeSettled) throw e
            srScalar = !srScalar
            srShapeSettled = true
            Log.w(TAG, "retrying VAD with sr as ${if (srScalar) "scalar" else "shape [1]"}", e)
            return runWithSampleRate(input, stateIn, srScalar)
        }
    }

    private fun runWithSampleRate(input: OnnxTensor, stateIn: OnnxTensor, scalar: Boolean): Float {
        val shape = if (scalar) longArrayOf() else longArrayOf(1)
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE_HZ.toLong())),
            shape,
        )
        return try {
            session.run(mapOf("input" to input, "state" to stateIn, "sr" to srTensor))
                .use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val prob = (results[0].value as Array<FloatArray>)[0][0]
                    @Suppress("UNCHECKED_CAST")
                    state = flatten(results[1].value as Array<Array<FloatArray>>)
                    srShapeSettled = true
                    prob
                }
        } finally {
            srTensor.close()
        }
    }

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

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "SileroVad"
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val STATE_WIDTH = 128

        /** Silero v5 is fixed at 512 samples per frame at 16kHz (32ms). */
        const val FRAME_SAMPLES = 512
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_DURATION_MS = FRAME_SAMPLES * 1000L / SAMPLE_RATE_HZ

        private const val ENTER_THRESHOLD = 0.5f
        private const val EXIT_THRESHOLD = 0.35f
    }
}
