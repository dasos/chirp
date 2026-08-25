package com.chirp.network

/**
 * Heuristic for whether [host] is local/private, in which case plaintext HTTP is
 * tolerated (e.g. talking to Ollama on the same LAN). Anything else must use
 * HTTPS — see [AuthInterceptor].
 */
internal fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().trim()
    if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "10.0.2.2") return true
    if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home")) return true
    if (h.startsWith("10.")) return true
    if (h.startsWith("192.168.")) return true
    if (Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)) return true
    return false
}
