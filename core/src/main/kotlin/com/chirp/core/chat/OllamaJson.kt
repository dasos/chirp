package com.chirp.core.chat

import kotlinx.serialization.json.Json

/** Shared JSON configuration for Ollama request/response (de)serialization. */
val OllamaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}
