package com.chirp.core.model

/** Author of a chat message, mirroring Ollama's `role` field. */
enum class Role(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wireName == value } ?: USER
    }
}
