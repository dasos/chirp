package com.chirp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.chirp.core.session.SessionSettings
import com.chirp.core.session.SettingsProvider
import com.chirp.core.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores all settings in EncryptedSharedPreferences (so the basic-auth password
 * is encrypted at rest) and implements the :core [SettingsProvider]. Also keeps
 * [ConnectionConfigHolder] current so the network layer always uses the latest
 * server URL + credentials.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val connectionHolder: ConnectionConfigHolder,
    private val dispatchers: DispatcherProvider,
) : SettingsProvider {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Strong ref required: registerOnSharedPreferenceChangeListener keeps only a weak one.
    private val holderListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateConnectionHolder()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(holderListener)
        updateConnectionHolder()
    }

    /** Cold flow of the full settings; re-emits on any change. */
    val settings: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(dispatchers.io)

    override suspend fun current(): SessionSettings = withContext(dispatchers.io) {
        val s = read()
        SessionSettings(
            model = s.model,
            systemPrompt = s.systemPrompt.ifBlank { null },
            autoListen = s.autoListen,
            listeningTimeoutMs = s.listeningTimeoutMs,
            ttsSpeed = s.ttsSpeed,
            ttsVoiceId = s.ttsVoiceId,
        )
    }

    fun connectionConfig(): ConnectionConfig {
        val s = read()
        return ConnectionConfig(
            baseUrl = normalizeUrl(s.serverUrl),
            username = s.authUsername.ifBlank { null },
            password = s.authPassword.ifBlank { null },
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) = withContext(dispatchers.io) {
        val updated = transform(read())
        prefs.edit().apply {
            putString(KEY_SERVER_URL, updated.serverUrl)
            putString(KEY_AUTH_USER, updated.authUsername)
            putString(KEY_AUTH_PASS, updated.authPassword)
            putString(KEY_MODEL, updated.model)
            putString(KEY_SYSTEM_PROMPT, updated.systemPrompt)
            putFloat(KEY_TTS_SPEED, updated.ttsSpeed)
            putString(KEY_TTS_VOICE, updated.ttsVoiceId)
            putBoolean(KEY_AUTO_LISTEN, updated.autoListen)
            putLong(KEY_LISTEN_TIMEOUT, updated.listeningTimeoutMs)
        }.apply()
        updateConnectionHolder()
    }

    private fun read(): AppSettings = AppSettings(
        serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
        authUsername = prefs.getString(KEY_AUTH_USER, "").orEmpty(),
        authPassword = prefs.getString(KEY_AUTH_PASS, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, AppSettings.DEFAULT_SYSTEM_PROMPT)
            ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
        ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.0f),
        ttsVoiceId = prefs.getString(KEY_TTS_VOICE, null),
        autoListen = prefs.getBoolean(KEY_AUTO_LISTEN, true),
        listeningTimeoutMs = prefs.getLong(KEY_LISTEN_TIMEOUT, 2_000L),
    )

    private fun updateConnectionHolder() = connectionHolder.update(connectionConfig())

    private fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

    companion object {
        private const val PREFS_FILE = "chirp_secure_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_USER = "auth_user"
        private const val KEY_AUTH_PASS = "auth_pass"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_LISTEN_TIMEOUT = "listen_timeout"
    }
}
