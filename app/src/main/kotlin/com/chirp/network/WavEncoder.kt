package com.chirp.network

import java.io.ByteArrayOutputStream

/**
 * Wraps raw mono PCM16 samples in a minimal RIFF/WAVE container, which is what
 * the transcription endpoint expects to receive as an upload.
 */
internal object WavEncoder {

    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1.toShort()
    private const val CHANNELS = 1.toShort()
    private const val BITS_PER_SAMPLE = 16.toShort()

    fun encode(samples: ShortArray, sampleRateHz: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(HEADER_BYTES + dataBytes)

        out.ascii("RIFF")
        out.int32(36 + dataBytes) // chunk size: everything after this field
        out.ascii("WAVE")

        out.ascii("fmt ")
        out.int32(16) // PCM subchunk size
        out.int16(PCM_FORMAT)
        out.int16(CHANNELS)
        out.int32(sampleRateHz)
        out.int32(sampleRateHz * CHANNELS * BITS_PER_SAMPLE / 8) // byte rate
        out.int16((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
        out.int16(BITS_PER_SAMPLE)

        out.ascii("data")
        out.int32(dataBytes)
        for (sample in samples) out.int16(sample)

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(value: String) {
        for (c in value) write(c.code)
    }

    /** RIFF is little-endian throughout. */
    private fun ByteArrayOutputStream.int32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.int16(value: Short) {
        val v = value.toInt()
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }
}
