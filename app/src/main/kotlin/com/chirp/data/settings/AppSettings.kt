package com.chirp.data.settings

/** Typed view over the user settings stored in [SettingsRepository]. */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Bearer API key for the chat endpoint. */
    val apiKey: String = "",
    val model: String = "",
    /** Transcription model used by the speech-to-text pipeline. */
    val sttModel: String = DEFAULT_STT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val ttsSpeed: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val autoListen: Boolean = true,
    val startListeningOnNewConversation: Boolean = true,
    val listeningTimeoutMs: Long = 2_000L,
    val webSearch: Boolean = false,
    val includeDateTime: Boolean = true,
    val includeLocation: Boolean = false,
) {
    companion object {
        /**
         * Any OpenAI-compatible endpoint works here (e.g. a self-hosted LiteLLM
         * gateway, an Ollama reverse proxy, or direct to OpenAI).
         */
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        /**
         * Speech-to-text model. Served from the same base URL and API key as the
         * chat calls, so no extra credential is needed. List alternatives with
         * `GET /models?output_modalities=transcription`.
         */
        const val DEFAULT_STT_MODEL = "openai/whisper-1"

        /**
         * Default system instruction for new conversations. Emphasizes conciseness
         * because replies are spoken aloud.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, concise voice assistant. Keep replies short and " +
                "conversational because they are spoken aloud while the user is walking."
    }

    val hasAuth: Boolean get() = apiKey.isNotBlank()
}

/**
 * Minimal snapshot of connection settings needed by the networking layer.
 */
data class ConnectionConfig(
    val baseUrl: String,
    val apiKey: String?,
) {
    val hasAuth: Boolean get() = !apiKey.isNullOrBlank()
}
