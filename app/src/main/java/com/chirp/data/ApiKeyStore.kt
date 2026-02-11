package com.chirp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ApiKeyStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "chirp_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val keyFlow = MutableStateFlow(prefs.getString(KEY_API, null))

    fun keyFlow(): StateFlow<String?> = keyFlow

    fun getKey(): String? = prefs.getString(KEY_API, null)

    fun setKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
        keyFlow.value = value.trim()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_API).apply()
        keyFlow.value = null
    }

    companion object {
        private const val KEY_API = "openai_api_key"
    }
}
