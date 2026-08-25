package com.chirp.core.session

/**
 * Read-only view of the settings the [SessionController] needs at runtime.
 * Implemented in the app module on top of EncryptedSharedPreferences.
 */
interface SettingsProvider {
    suspend fun current(): SessionSettings
}

data class SessionSettings(
    val model: String,
    val systemPrompt: String?,
    val autoListen: Boolean = true,
    val listeningTimeoutMs: Long = 2_000L,
    val ttsSpeed: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val temperature: Double? = null,
    /** Ask the backend's server-side web search to ground replies. */
    val webSearch: Boolean = false,
    /** Max consecutive no-match turns before the loop pauses itself. */
    val maxNoMatchRetries: Int = 2,
    /** Network retry policy for streaming chat. */
    val maxStreamRetries: Int = 2,
    val retryBackoffMs: Long = 1_500L,
)
