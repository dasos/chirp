package com.chirp.network

import com.chirp.data.settings.ConnectionConfigHolder
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds `Authorization: Bearer <key>` to **every** request when an API key is
 * configured, and refuses to talk to a non-local host over plaintext HTTP (so
 * the key is never sent in the clear). The default endpoint is OpenRouter; a
 * self-hosted gateway behind HTTPS works identically.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val connectionHolder: ConnectionConfigHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.scheme.equals("http", ignoreCase = true) && !isLocalHost(url.host)) {
            throw IOException(
                "Refusing to send requests over plaintext HTTP to a non-local host " +
                    "(${url.host}). Configure an https:// URL.",
            )
        }

        val config = connectionHolder.config
        val builder = request.newBuilder()
        if (config.hasAuth) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return chain.proceed(builder.build())
    }
}
