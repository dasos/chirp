package com.chirp.data.settings

/** All user-configurable settings (UI-facing). Secrets live in EncryptedSharedPreferences. */
data class AppSettings(
    val serverUrl: String = "",
    val authUsername: String = "",
    val authPassword: String = "",
    val model: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val ttsSpeed: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val autoListen: Boolean = true,
    val listeningTimeoutMs: Long = 2_000L,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, concise voice assistant. Keep replies short and " +
                "conversational because they are spoken aloud while the user is walking."
    }
}

/** Resolved server connection details used by the network layer. */
data class ConnectionConfig(
    /** Normalized base URL with no trailing slash, e.g. https://ollama.example.com */
    val baseUrl: String,
    val username: String?,
    val password: String?,
) {
    val hasAuth: Boolean get() = !username.isNullOrEmpty() && password != null
}
