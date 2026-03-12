package com.chirp.ask

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class PushToTalkRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): Boolean {
        if (recorder != null) return true

        val file = File.createTempFile("chirp-ask-", ".m4a", context.cacheDir)
        val nextRecorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(64_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        outputFile = file
        recorder = nextRecorder
        return true
    }

    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val file = outputFile
        return try {
            activeRecorder.stop()
            file
        } catch (_: Exception) {
            file?.delete()
            null
        } finally {
            release()
        }
    }

    fun cancel() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            outputFile?.delete()
            release()
        }
    }

    private fun release() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        outputFile = null
    }
}
