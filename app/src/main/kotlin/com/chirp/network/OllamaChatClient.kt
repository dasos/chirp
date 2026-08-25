package com.chirp.network

import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatRequestSpec
import com.chirp.core.chat.ChatStreamEvent
import com.chirp.core.chat.ConnectionResult
import com.chirp.core.chat.OllamaException
import com.chirp.core.chat.OllamaJson
import com.chirp.core.chat.OllamaModel
import com.chirp.core.chat.OllamaStreamParser
import com.chirp.core.chat.OllamaChatRequest
import com.chirp.core.chat.OllamaChatResponseChunk
import com.chirp.core.chat.OllamaMessageDto
import com.chirp.core.chat.OllamaOptions
import com.chirp.core.chat.OllamaTagsResponse
import com.chirp.core.chat.OllamaVersionResponse
import com.chirp.data.settings.ConnectionConfigHolder
import com.chirp.core.util.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp-based [ChatClient] for an Ollama-compatible server. Streams `/api/chat`
 * NDJSON line-by-line so the [com.chirp.core.session.SessionController] can speak
 * the first sentence early. Base URL + auth come from [ConnectionConfigHolder]
 * (kept current by settings), and the [AuthInterceptor] attaches credentials.
 */
@Singleton
class OllamaChatClient @Inject constructor(
    private val client: OkHttpClient,
    private val connectionHolder: ConnectionConfigHolder,
    private val dispatchers: DispatcherProvider,
) : ChatClient {

    override fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent> = flow {
        val base = requireBaseUrl()
        val payload = OllamaChatRequest(
            model = spec.model,
            messages = spec.messages.map { OllamaMessageDto(it.role.wireName, it.text) },
            stream = true,
            options = buildOptions(spec),
        )
        val request = Request.Builder()
            .url("$base/api/chat")
            .post(OllamaJson.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // Disable the read timeout for the (potentially long) streaming response.
        val streamingClient = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = streamingClient.newCall(request)

        // Make blocking reads cancellable: cancel the call when the coroutine ends.
        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            runCatching { call.cancel() }
        }

        val response = try {
            call.execute()
        } catch (e: IOException) {
            completionHandle?.dispose()
            throw OllamaException(e.message ?: "Could not reach the server", e)
        }

        try {
            if (!response.isSuccessful) throw OllamaException(httpErrorMessage(response))
            val source = response.body?.source() ?: throw OllamaException("Empty response body")
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val event = OllamaStreamParser.parse(line)
                if (event != null) emit(event)
                if (event is ChatStreamEvent.Completed) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OllamaException) {
            throw e
        } catch (e: IOException) {
            throw OllamaException(e.message ?: "Connection lost", e)
        } finally {
            completionHandle?.dispose()
            response.close()
        }
    }.flowOn(dispatchers.io)

    override suspend fun listModels(): List<OllamaModel> = withContext(dispatchers.io) {
        val base = requireBaseUrl()
        val request = Request.Builder().url("$base/api/tags").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw OllamaException(httpErrorMessage(response))
            val body = response.body?.string().orEmpty()
            val tags = OllamaJson.decodeFromString<OllamaTagsResponse>(body)
            tags.models.map { dto ->
                OllamaModel(
                    name = dto.name,
                    parameterSize = dto.details?.parameterSize,
                    family = dto.details?.family,
                    sizeBytes = dto.size,
                )
            }
        }
    }

    override suspend fun testConnection(): ConnectionResult = withContext(dispatchers.io) {
        val base = connectionHolder.config.baseUrl
        if (base.isBlank()) return@withContext ConnectionResult.Failure("Server URL is not set.")
        try {
            val version = runCatching {
                client.newCall(Request.Builder().url("$base/api/version").get().build()).execute().use { r ->
                    if (r.isSuccessful) {
                        OllamaJson.decodeFromString<OllamaVersionResponse>(r.body?.string().orEmpty()).version
                    } else {
                        null
                    }
                }
            }.getOrNull()
            val models = listModels()
            ConnectionResult.Success(version, models.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Connection failed.")
        }
    }

    private fun requireBaseUrl(): String {
        val base = connectionHolder.config.baseUrl
        if (base.isBlank()) {
            throw OllamaException("Server URL is not set. Open Settings to configure it.")
        }
        return base
    }

    private fun buildOptions(spec: ChatRequestSpec): OllamaOptions? {
        if (spec.temperature == null && spec.numCtx == null) return null
        return OllamaOptions(temperature = spec.temperature, numCtx = spec.numCtx)
    }

    private fun httpErrorMessage(response: Response): String {
        val code = response.code
        val detail = runCatching { response.peekBody(1024).string() }
            .getOrNull()
            ?.let { raw -> runCatching { OllamaJson.decodeFromString<OllamaChatResponseChunk>(raw).error }.getOrNull() }
        return when (code) {
            401 -> "Authentication failed (401). Check your username and password."
            403 -> "Access forbidden (403)."
            404 -> "Not found (404). Check the server URL and the selected model."
            in 500..599 -> "Server error ($code). ${detail.orEmpty()}".trim()
            else -> "Request failed ($code). ${detail.orEmpty()}".trim()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
