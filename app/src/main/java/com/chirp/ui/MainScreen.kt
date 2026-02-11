package com.chirp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chirp.data.TranscriptEntity
import com.chirp.realtime.SessionStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onRequestMic: () -> Unit,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chirp") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showKey = true }) {
                        Icon(Icons.Filled.Key, contentDescription = "API Key")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            StatusCard(
                status = sessionState.message,
                isLive = sessionState.status == SessionStatus.CONNECTED,
                onConnect = {
                    if (apiKey.isNullOrBlank()) {
                        showKey = true
                    } else {
                        onRequestMic()
                        onStartSession()
                    }
                },
                onDisconnect = { onStopSession() },
                isConnecting = sessionState.status == SessionStatus.CONNECTING,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Transcript", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { viewModel.clearTranscripts() }) {
                    Text("Clear all")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TranscriptList(transcripts = transcripts, onDelete = viewModel::deleteTranscript)
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onDismiss = { showSettings = false },
            onUpdate = viewModel::updateSettings,
        )
    }

    if (showKey) {
        ApiKeySheet(
            currentKey = apiKey,
            onDismiss = { showKey = false },
            onSave = {
                viewModel.saveApiKey(it)
                showKey = false
            },
            onClear = {
                viewModel.clearApiKey()
                showKey = false
            },
        )
    }
}

@Composable
private fun StatusCard(
    status: String,
    isLive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
        ),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isLive) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
                            ),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = if (isLive) "Live" else if (isConnecting) "Connecting" else "Idle",
                        style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onPrimary),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = if (isLive || isConnecting) onDisconnect else onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Icon(
                            imageVector = if (isLive || isConnecting) Icons.Filled.StopCircle else Icons.Filled.WifiTethering,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (isLive || isConnecting) "Disconnect" else "Connect",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("Audio only") },
                        leadingIcon = null,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                            labelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptList(
    transcripts: List<TranscriptEntity>,
    onDelete: (String) -> Unit,
) {
    if (transcripts.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("No messages yet.")
                Text("Start a session to see transcripts.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(transcripts, key = { it.itemId }) { item ->
            TranscriptRow(item = item, onDelete = { onDelete(item.itemId) })
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun TranscriptRow(item: TranscriptEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (item.role == "assistant") "Assistant" else "You",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: com.chirp.data.UserSettings,
    onDismiss: () -> Unit,
    onUpdate: ((com.chirp.data.UserSettings) -> com.chirp.data.UserSettings) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Session Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            SettingToggle(
                title = "Low bandwidth audio (G.711)",
                checked = settings.lowBandwidth,
                onCheckedChange = { checked -> onUpdate { it.copy(lowBandwidth = checked) } },
            )
            SettingToggle(
                title = "Enable transcription",
                checked = settings.transcribe,
                onCheckedChange = { checked -> onUpdate { it.copy(transcribe = checked) } },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Max output tokens: ${settings.maxOutputTokens}")
            Slider(
                value = settings.maxOutputTokens.toFloat(),
                onValueChange = { value ->
                    onUpdate { it.copy(maxOutputTokens = value.toInt().coerceIn(80, 500)) }
                },
                valueRange = 80f..500f,
                steps = 7,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeySheet(
    currentKey: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var keyInput by remember { mutableStateOf(currentKey.orEmpty()) }

    LaunchedEffect(currentKey) {
        keyInput = currentKey.orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("OpenAI API Key", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Your key is stored locally on this device. OpenAI discourages using API keys in client apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("sk-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (keyInput.isNotBlank()) onSave(keyInput) }) {
                    Text("Save")
                }
                FilledTonalButton(onClick = onClear) {
                    Text("Clear")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
