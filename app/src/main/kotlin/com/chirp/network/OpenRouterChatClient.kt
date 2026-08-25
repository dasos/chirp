package com.chirp.network

import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatCompletionChunk
import com.chirp.core.chat.ChatCompletionRequest
import com.chirp.core.chat.ChatException
import com.chirp.core.chat.ChatJson
import com.chirp.core.chat.ChatModel
import com.chirp.core.chat.ChatRequestSpec
import com.chirp.core.chat.ChatStreamEvent
import com.chirp.core.chat.ConnectionResult
import com.chirp.core.chat.ModelListResponse
import com.chirp.core.chat.OpenAiStreamParser
import com.chirp.core.chat.StreamOptions
import com.chirp.core.chat.WireMessage
import com.chirp.core.chat.WireTool
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
 * OkHttp-based [ChatClient] for OpenRouter's `/chat/completions` SSE API — and,
 * via the base-URL setting, any other endpoint speaking the same OpenAI-compatible
 * wire format (e.g. a self-hosted LiteLLM gateway). Streams line-by-line so the
 * [com.chirp.core.session.SessionController] can speak the first sentence early.
 * Base URL + bearer key come from [ConnectionConfigHolder] (kept current by
 * settings); the [AuthInterceptor] attaches the key.
 */
@Singleton
class OpenRouterChatClient @Inject constructor(
    private val client: OkHttpClient,
    private val connectionHolder: ConnectionConfigHolder,
    private val dispatchers: DispatcherProvider,
) : ChatClient {

    override fun streamChat(spec: ChatRequestSpec): Flow<ChatStreamEvent> = flow {
        val base = requireBaseUrl()
        val payload = ChatCompletionRequest(
            model = spec.model,
            messages = spec.messages.map { WireMessage(it.role.wireName, it.text) },
            stream = true,
            temperature = spec.temperature,
            streamOptions = StreamOptions(includeUsage = true),
            tools = if (spec.webSearch) listOf(WireTool(type = WEB_SEARCH_TOOL)) else null,
        )
        val request = Request.Builder()
            .url("$base/chat/completions")
            .post(ChatJson.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .header("HTTP-Referer", SITE_URL)
            .header("X-Title", SITE_TITLE)
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
            throw ChatException(e.message ?: "Could not reach the server", e)
        }

        try {
            if (!response.isSuccessful) throw ChatException(httpErrorMessage(response))
            val source = response.body?.source() ?: throw ChatException("Empty response body")
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val event = OpenAiStreamParser.parse(line)
                if (event != null) emit(event)
                if (event is ChatStreamEvent.Completed) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatException) {
            throw e
        } catch (e: IOException) {
            throw ChatException(e.message ?: "Connection lost", e)
        } finally {
            completionHandle?.dispose()
            response.close()
        }
    }.flowOn(dispatchers.io)

    override suspend fun listModels(): List<ChatModel> = withContext(dispatchers.io) {
        val base = requireBaseUrl()
        val request = Request.Builder().url("$base/models").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ChatException(httpErrorMessage(response))
            val body = response.body?.string().orEmpty()
            ChatJson.decodeFromString<ModelListResponse>(body).data.map { dto ->
                ChatModel(id = dto.id, label = dto.name)
            }
        }
    }

    override suspend fun testConnection(): ConnectionResult = withContext(dispatchers.io) {
        val config = connectionHolder.config
        if (config.baseUrl.isBlank()) return@withContext ConnectionResult.Failure("Server URL is not set.")
        if (!config.hasAuth) return@withContext ConnectionResult.Failure("API key is not set.")
        try {
            val models = listModels()
            ConnectionResult.Success(version = null, modelCount = models.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Failure(e.message ?: "Connection failed.")
        }
    }

    private fun requireBaseUrl(): String {
        val base = connectionHolder.config.baseUrl
        if (base.isBlank()) {
            throw ChatException("API base URL is not set. Open Settings to configure it.")
        }
        return base
    }

    private fun httpErrorMessage(response: Response): String {
        val code = response.code
        val detail = runCatching { response.peekBody(1024).string() }
            .getOrNull()
            ?.let { raw ->
                runCatching { ChatJson.decodeFromString<ChatCompletionChunk>(raw).errorMessage }
                    .getOrNull()
            }
        return when (code) {
            401 -> "Authentication failed (401). Check your API key."
            402 -> "Insufficient credits (402). Top up your account."
            403 -> "Access forbidden (403)."
            404 -> "Not found (404). Check the API URL and the selected model."
            429 -> "Rate limited (429). Try again shortly."
            in 500..599 -> "Server error ($code). ${detail.orEmpty()}".trim()
            else -> "Request failed ($code). ${detail.orEmpty()}".trim()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** OpenRouter server tool that lets the model search the web when useful. */
        private const val WEB_SEARCH_TOOL = "openrouter:web_search"

        /** Optional attribution headers OpenRouter asks clients to send. */
        private const val SITE_URL = "https://github.com/dasos/chirp"
        private const val SITE_TITLE = "Chirp"
    }
}
