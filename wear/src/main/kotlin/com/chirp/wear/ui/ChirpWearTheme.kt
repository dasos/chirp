package com.chirp.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme

@Composable
fun ChirpWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content,
    )
}