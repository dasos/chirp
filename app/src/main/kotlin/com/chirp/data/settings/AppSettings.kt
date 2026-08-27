package com.chirp.data.settings

/** All user-configurable settings (UI-facing). Secrets live in EncryptedSharedPreferences. */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Bearer API key for the chat endpoint. */
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val ttsSpeed: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val autoListen: Boolean = true,
    val startListeningOnNewConversation: Boolean = true,
    val listeningTimeoutMs: Long = 2_000L,
    val webSearch: Boolean = false,
) {
    companion object {
        /**
         * Any OpenAI-compatible endpoint works here (e.g. a self-hosted LiteLLM
         * gateway); the default is OpenRouter's public API.
         */
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, concise voice assistant. Keep replies short and " +
                "conversational because they are spoken aloud while the user is walking."
    }
}

/** Resolved server connection details used by the network layer. */
data class ConnectionConfig(
    /** Normalized base URL with no trailing slash, e.g. https://openrouter.ai/api/v1 */
    val baseUrl: String,
    val apiKey: String?,
) {
    val hasAuth: Boolean get() = !apiKey.isNullOrEmpty()
}
