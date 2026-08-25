package com.chirp.ui.conversation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chirp.core.model.Role
import com.chirp.core.session.SessionEvent
import com.chirp.core.session.SessionPhase
import com.chirp.ui.components.MessageBubble
import com.chirp.ui.components.MicStatusIndicator
import com.chirp.ui.permissions.conversationPermissions
import com.chirp.ui.permissions.hasMicPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val title by viewModel.conversationTitle.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Starting the microphone foreground service requires RECORD_AUDIO on Android 14+,
    // so any session start (mic tap or typing while idle) is gated behind the permission.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (context.hasMicPermission()) pendingAction?.invoke()
        pendingAction = null
    }

    fun requireMicThen(action: () -> Unit) {
        if (context.hasMicPermission()) {
            action()
        } else {
            pendingAction = action
            micPermissionLauncher.launch(conversationPermissions)
        }
    }

    val onMic = { requireMicThen { viewModel.onMicTap() } }

    val onSend: () -> Unit = {
        val text = input
        input = ""
        if (state.active) viewModel.sendText(text) else requireMicThen { viewModel.sendText(text) }
    }

    // Haptics on listen start/stop.
    LaunchedEffectEvents(viewModel.events, haptics)

    // Keep the transcript pinned to the latest message / streaming text.
    androidx.compose.runtime.LaunchedEffect(messages.size, state.partialResponse, state.partialTranscript) {
        val count = visibleItemCount(messages.size, state.phase, state.partialResponse, state.partialTranscript)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
            ) {
                items(
                    items = messages.filter { it.role != Role.SYSTEM },
                    key = { it.id },
                ) { message ->
                    MessageBubble(role = message.role, text = message.text)
                }

                // Live streaming assistant bubble.
                if (state.partialResponse.isNotBlank() &&
                    (state.phase == SessionPhase.THINKING || state.phase == SessionPhase.SPEAKING)
                ) {
                    item(key = "partial-assistant") {
                        MessageBubble(role = Role.ASSISTANT, text = state.partialResponse, dimmed = true)
                    }
                }

                // Live partial transcript while listening.
                if (state.phase == SessionPhase.LISTENING && state.partialTranscript.isNotBlank()) {
                    item(key = "partial-user") {
                        MessageBubble(role = Role.USER, text = state.partialTranscript, dimmed = true)
                    }
                }
            }

            if (state.phase == SessionPhase.ERROR && state.errorMessage != null) {
                Text(
                    text = state.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            MicStatusIndicator(
                phase = state.phase,
                rms = state.rms,
                thinkingStartedAtMillis = state.thinkingStartedAtMillis,
                onTap = onMic,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )

            Text(
                text = statusLabel(state.phase),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            ControlsRow(
                active = state.active,
                paused = state.phase == SessionPhase.PAUSED,
                speaking = state.phase == SessionPhase.SPEAKING,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onStopSpeaking = viewModel::stopSpeaking,
                onEnd = viewModel::endSession,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Type instead of speaking…") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ControlsRow(
    active: Boolean,
    paused: Boolean,
    speaking: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopSpeaking: () -> Unit,
    onEnd: () -> Unit,
) {
    if (!active) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paused) {
            Button(onClick = onResume) { Text("Resume") }
        } else {
            OutlinedButton(onClick = onPause) { Text("Pause") }
        }
        if (speaking) {
            OutlinedButton(onClick = onStopSpeaking) { Text("Stop speaking") }
        }
        TextButton(onClick = onEnd) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Text("End")
        }
    }
}

@Composable
private fun LaunchedEffectEvents(
    events: kotlinx.coroutines.flow.Flow<SessionEvent>,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                SessionEvent.ListeningStarted -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                SessionEvent.ListeningStopped -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                else -> Unit
            }
        }
    }
}

private fun statusLabel(phase: SessionPhase): String = when (phase) {
    SessionPhase.IDLE -> "Tap the mic to start"
    SessionPhase.LISTENING -> "Listening…"
    SessionPhase.THINKING -> "Thinking…"
    SessionPhase.SPEAKING -> "Speaking…"
    SessionPhase.PAUSED -> "Paused"
    SessionPhase.ERROR -> "Tap the mic to retry"
}

private fun visibleItemCount(
    messageCount: Int,
    phase: SessionPhase,
    partialResponse: String,
    partialTranscript: String,
): Int {
    var count = messageCount
    if (partialResponse.isNotBlank() && (phase == SessionPhase.THINKING || phase == SessionPhase.SPEAKING)) count++
    if (phase == SessionPhase.LISTENING && partialTranscript.isNotBlank()) count++
    return count
}
