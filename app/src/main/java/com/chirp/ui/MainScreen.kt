package com.chirp.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chirp.ask.AskStatus
import com.chirp.data.SessionEntity
import com.chirp.data.TranscriptEntity
import com.chirp.realtime.SessionStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onRequestMic: () -> Unit,
    onNewSession: () -> Unit,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val selectedSession by viewModel.selectedSession.collectAsStateWithLifecycle()
    val isViewingExistingSession by viewModel.isViewingExistingSession.collectAsStateWithLifecycle()
    val askState by viewModel.askState.collectAsStateWithLifecycle()
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = drawerState.isOpen || isViewingExistingSession) {
        if (isViewingExistingSession) {
            viewModel.returnToDefaultSession()
        }
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sessions", style = MaterialTheme.typography.titleMedium)
                        FilledTonalButton(
                            onClick = {
                                viewModel.startNewSession()
                                scope.launch { drawerState.close() }
                            },
                        ) {
                            Text("New session")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SessionList(
                        sessions = sessions,
                        selectedSessionId = selectedSession?.sessionId,
                        onSelect = {
                            viewModel.selectSession(it)
                            scope.launch { drawerState.close() }
                        },
                        onDelete = viewModel::deleteSession,
                        modifier = Modifier.weight(1f, fill = true),
                        fixedHeight = null,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = { viewModel.clearTranscripts() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete all")
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Chirp") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open sessions")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewSession) {
                            Icon(Icons.Filled.Add, contentDescription = "New session")
                        }
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
                    status = if (askState.status == AskStatus.IDLE) sessionState.message else askState.message,
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
                    connectLabel = if (isViewingExistingSession) "Continue" else "Talk",
                    onAskStart = {
                        if (apiKey.isNullOrBlank()) {
                            showKey = true
                        } else {
                            onRequestMic()
                            viewModel.beginAsk()
                        }
                    },
                    onAskEnd = viewModel::finishAsk,
                    onAskCancel = viewModel::cancelAsk,
                    askEnabled = sessionState.status == SessionStatus.IDLE && askState.status != AskStatus.PROCESSING,
                    isAskRecording = askState.status == AskStatus.RECORDING,
                )

                Spacer(modifier = Modifier.height(10.dp))

                TranscriptList(transcripts = transcripts)
            }
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
    connectLabel: String,
    onAskStart: () -> Unit,
    onAskEnd: () -> Unit,
    onAskCancel: () -> Unit,
    askEnabled: Boolean,
    isAskRecording: Boolean,
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
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(16.dp),
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
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = if (isLive || isConnecting) onDisconnect else onConnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary),
                        enabled = !isAskRecording,
                    ) {
                        Icon(
                            imageVector = if (isLive || isConnecting) Icons.Filled.StopCircle else Icons.Filled.WifiTethering,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (isLive || isConnecting) "Disconnect" else connectLabel,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HoldToAskButton(
                        enabled = askEnabled,
                        isRecording = isAskRecording,
                        onPressStart = onAskStart,
                        onPressEnd = onAskEnd,
                        onPressCancel = onAskCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldToAskButton(
    enabled: Boolean,
    isRecording: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.13f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "askScale",
    )

    val currentEnabled by rememberUpdatedState(enabled)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)
    val currentOnPressCancel by rememberUpdatedState(onPressCancel)

    val active = enabled || isRecording
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .scale(scale)
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (currentIsRecording) {
                            // Second tap while recording — stop
                            currentOnPressEnd()
                            return@detectTapGestures
                        }
                        if (!currentEnabled) return@detectTapGestures

                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        playChirp(context)
                        currentOnPressStart()

                        val startTime = System.currentTimeMillis()
                        val released = tryAwaitRelease()
                        val holdMs = System.currentTimeMillis() - startTime

                        when {
                            !released -> currentOnPressCancel()
                            holdMs >= HOLD_THRESHOLD_MS -> currentOnPressEnd()
                            // Quick tap: recording stays active; next press stops it
                        }
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            )
            Text(
                text = if (isRecording) "Tap to stop" else "Ask",
                color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun playChirp(context: Context) {
    try {
        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME / 2)
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toneGen.release() }, 300)
    } catch (_: Exception) {}
}

private const val HOLD_THRESHOLD_MS = 350L

@Composable
private fun SessionList(
    sessions: List<SessionEntity>,
    selectedSessionId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    fixedHeight: androidx.compose.ui.unit.Dp? = 160.dp,
) {
    if (sessions.isEmpty()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("No sessions yet.")
                Text("Tap Talk or tap/hold Ask to start one.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fixedHeight == null) Modifier else Modifier.height(fixedHeight),
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(sessions, key = { it.sessionId }) { session ->
            SessionRow(
                session = session,
                isSelected = session.sessionId == selectedSessionId,
                onSelect = { onSelect(session.sessionId) },
                onDelete = { onDelete(session.sessionId) },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
    val label = remember(session.updatedAt) { dateFormat.format(Date(session.updatedAt)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title?.ifBlank { "Session" } ?: "Session",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete session")
            }
        }
    }
}

@Composable
private fun TranscriptList(
    transcripts: List<TranscriptEntity>,
) {
    if (transcripts.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("No messages yet.")
                Text("Start talking to add to the transcript.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(transcripts, key = { it.itemId }) { item ->
            TranscriptRow(item = item)
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun TranscriptRow(item: TranscriptEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (item.role == "assistant") "Assistant" else "You",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
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
                title = "Use speakerphone",
                checked = settings.speakerphone,
                onCheckedChange = { checked -> onUpdate { it.copy(speakerphone = checked) } },
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
                "Your key is stored locally on this device.",
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
