package com.chirp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("chirp_settings", Context.MODE_PRIVATE)

    private val settingsFlow = MutableStateFlow(load())

    fun settingsFlow(): StateFlow<UserSettings> = settingsFlow

    fun update(block: (UserSettings) -> UserSettings) {
        val next = block(settingsFlow.value)
        persist(next)
        settingsFlow.value = next
    }

    private fun load(): UserSettings {
        return UserSettings(
            lowBandwidth = prefs.getBoolean(KEY_LOW_BW, false),
            transcribe = prefs.getBoolean(KEY_TRANSCRIBE, true),
            maxOutputTokens = prefs.getInt(KEY_MAX_TOKENS, 200),
        )
    }

    private fun persist(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_LOW_BW, settings.lowBandwidth)
            .putBoolean(KEY_TRANSCRIBE, settings.transcribe)
            .putInt(KEY_MAX_TOKENS, settings.maxOutputTokens)
            .apply()
    }

    companion object {
        private const val KEY_LOW_BW = "low_bw"
        private const val KEY_TRANSCRIBE = "transcribe"
        private const val KEY_MAX_TOKENS = "max_tokens"
    }
}

data class UserSettings(
    val lowBandwidth: Boolean,
    val transcribe: Boolean,
    val maxOutputTokens: Int,
)
