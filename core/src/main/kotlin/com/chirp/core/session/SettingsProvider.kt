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
    val temperature: Double? = null,
    val autoListen: Boolean = true,
    val listeningTimeoutMs: Long = 2_000L,
    val ttsSpeed: Float = 1.0f,
    val ttsVoiceId: String? = null,
    val maxNoMatchRetries: Int = 1,
    val maxStreamRetries: Int = 3,
    val retryBackoffMs: Long = 500L,
    val webSearch: Boolean = false,
    val includeDateTime: Boolean = true,
    val includeLocation: Boolean = false,
)
