package com.chirp.network

import com.chirp.data.settings.ConnectionConfigHolder
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds HTTP Basic auth to **every** request when credentials are configured, and
 * refuses to talk to a non-local host over plaintext HTTP (so credentials are
 * never sent in the clear to a remote server). The user runs Ollama behind a
 * reverse proxy with HTTPS + basic auth; this guarantees the header is always
 * attached and the transport is encrypted.
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
            builder.header("Authorization", Credentials.basic(config.username!!, config.password!!))
        }
        return chain.proceed(builder.build())
    }
}
