package com.chirp.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import com.chirp.core.session.SessionPhase
import com.chirp.core.session.SessionState

@Composable
fun WearScreen(
    state: SessionState?,
    onBigButton: () -> Unit,
) {
    Scaffold(
        timeText = { TimeText() },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (statusText(state) != null) {
                StatusPill(text = statusText(state)!!)
            }

            val interrupting = state?.isSpeaking == true || state?.isThinking == true
            Button(
                onClick = onBigButton,
                modifier = Modifier.padding(vertical = 12.dp).size(112.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (interrupting) {
                        MaterialTheme.colors.error
                    } else {
                        MaterialTheme.colors.primary
                    },
                ),
            ) {
                Icon(
                    imageVector = if (interrupting) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colors.onPrimary,
                )
            }

            Text(
                text = hint(state),
                style = MaterialTheme.typography.caption2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .background(color = MaterialTheme.colors.primary, shape = CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = MaterialTheme.colors.onPrimary, style = MaterialTheme.typography.caption2)
    }
}

private fun statusText(state: SessionState?): String? = when {
    state == null -> null
    !state.active -> null
    else -> when (state.phase) {
        SessionPhase.LISTENING -> "Listening"
        SessionPhase.THINKING -> "Thinking…"
        SessionPhase.SPEAKING -> "Speaking"
        SessionPhase.PAUSED -> "Ready"
        SessionPhase.ERROR -> "Error"
        SessionPhase.IDLE -> "Idle"
    }
}

private fun hint(state: SessionState?): String = when {
    state == null -> "No phone connected — tap mic to start"
    !state.active -> "Tap mic to start"
    state.isListening ->
        state.partialTranscript.ifBlank { "Listening…" }
    state.isThinking || state.isSpeaking ->
        state.partialResponse.ifBlank { "Thinking…" }
    state.isPaused -> "Tap mic to resume"
    state.phase == SessionPhase.ERROR ->
        state.errorMessage?.take(80) ?: "Tap to retry"
    else -> ""
}