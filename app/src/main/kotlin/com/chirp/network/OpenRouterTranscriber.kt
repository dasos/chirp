package com.chirp.network

import com.chirp.core.speech.PcmClip
import com.chirp.core.speech.SttError
import com.chirp.core.speech.Transcriber
import com.chirp.core.speech.TranscriptionException
import com.chirp.core.util.DispatcherProvider
import com.chirp.data.settings.ConnectionConfigHolder
import com.chirp.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Transcriber] backed by OpenRouter's `/audio/transcriptions` endpoint — and,
 * via the base-URL setting, any other OpenAI-compatible gateway exposing the
 * same path.
 *
 * It deliberately reuses the *same* base URL, bearer key and [OkHttpClient]
 * as the chat calls: OpenRouter serves speech-to-text from the account the app is
 * already authenticated against, so there is no second credential to store, and
 * the shared [AuthInterceptor] keeps the plaintext-HTTP guard in force.
 *
 * The clip is uploaded as multipart WAV rather than base64 JSON, which avoids
 * base64's ~33% inflation on an already-large payload.
 */
@Singleton
class OpenRouterTranscriber @Inject constructor(
    private val client: OkHttpClient,
    private val connectionHolder: ConnectionConfigHolder,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) : Transcriber {

    override suspend fun transcribe(clip: PcmClip, languageTag: String?): String =
        withContext(dispatchers.io) {
            val base = connectionHolder.config.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) {
                throw TranscriptionException(
                    SttError.CLIENT,
                    "API base URL is not set. Open Settings to configure it.",
                )
            }

            val model = settings.sttModel()
            val wav = WavEncoder.encode(clip.samples, clip.sampleRateHz)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", "utterance.wav", wav.toRequestBody(WAV_MEDIA_TYPE))
                .apply {
                    languageTag?.substringBefore('-')?.takeIf { it.isNotBlank() }
                        ?.let { addFormDataPart("language", it) }
                }
                .build()

            val request = Request.Builder()
                .url("$base/audio/transcriptions")
                .post(body)
                .header("HTTP-Referer", SITE_URL)
                .header("X-Title", SITE_TITLE)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw TranscriptionException(
                            httpError(response.code),
                            "Transcription failed (HTTP ${response.code})",
                        )
                    }
                    parseTranscript(payload)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranscriptionException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw TranscriptionException(SttError.NETWORK_TIMEOUT, "Transcription timed out", e)
            } catch (e: IOException) {
                throw TranscriptionException(SttError.NETWORK, e.message ?: "Connection lost", e)
            } catch (e: Exception) {
                throw TranscriptionException(SttError.SERVER, "Could not read the transcription", e)
            }
        }

    /**
     * Pulls `text` out of the response. Parsed field-by-field rather than into a
     * generated serializer so the DTO cannot be stripped by R8 in release
     * builds, and so an unexpected extra field can never fail a whole turn.
     */
    private fun parseTranscript(payload: String): String {
        val root = JSON.parseToJsonElement(payload).jsonObject
        root["usage"]?.jsonObject?.get("cost")?.jsonPrimitive?.doubleOrNull?.let {
            Log.d(TAG, "transcription cost: $it")
        }
        return root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    private fun httpError(code: Int): SttError = when {
        code == 401 || code == 403 -> SttError.CLIENT
        code == 408 || code == 504 -> SttError.NETWORK_TIMEOUT
        code in 400..499 -> SttError.CLIENT
        else -> SttError.SERVER
    }

    private companion object {
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true }

        const val TAG = "OpenRouterTranscriber"
        const val SITE_URL = "https://github.com/dasos/chirp"
        const val SITE_TITLE = "Chirp"

    }
}
