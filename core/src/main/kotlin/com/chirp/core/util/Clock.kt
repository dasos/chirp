package com.chirp.core.util

/** Wall-clock indirection so time-dependent logic stays testable. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
