package com.chirp.data.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, always-current view of the server connection used by the OkHttp
 * [com.chirp.network.AuthInterceptor]. [SettingsRepository] keeps it in sync so
 * that auth + base URL changes take effect on the very next request.
 */
@Singleton
class ConnectionConfigHolder @Inject constructor() {
    @Volatile
    var config: ConnectionConfig = ConnectionConfig(baseUrl = "", username = null, password = null)
        private set

    fun update(newConfig: ConnectionConfig) {
        config = newConfig
    }
}
