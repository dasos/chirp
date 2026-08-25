package com.chirp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chirp.data.settings.AppSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val modelsLoading by viewModel.modelsLoading.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = settings
        if (loaded == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        SettingsContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            settings = loaded,
            models = models.map { it.name },
            voices = voices,
            modelsLoading = modelsLoading,
            connection = connection,
            onUpdate = viewModel::update,
            onLoadModels = viewModel::loadModels,
            onLoadVoices = viewModel::loadVoices,
            onTestConnection = viewModel::testConnection,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    modifier: Modifier,
    settings: AppSettings,
    models: List<String>,
    voices: List<com.chirp.core.speech.TtsVoice>,
    modelsLoading: Boolean,
    connection: ConnectionUiState,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLoadModels: () -> Unit,
    onLoadVoices: () -> Unit,
    onTestConnection: () -> Unit,
) {
    // Seed editable text fields once from the first loaded settings.
    var seeded by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!seeded) {
            serverUrl = settings.serverUrl
            username = settings.authUsername
            password = settings.authPassword
            systemPrompt = settings.systemPrompt
            seeded = true
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Server")
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; onUpdate { s -> s.copy(serverUrl = it) } },
            label = { Text("Server URL") },
            placeholder = { Text("https://ollama.example.com") },
            singleLine = true,
            supportingText = { Text("HTTPS is required for non-local hosts.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; onUpdate { s -> s.copy(authUsername = it) } },
            label = { Text("Basic auth username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; onUpdate { s -> s.copy(authPassword = it) } },
            label = { Text("Basic auth password (optional)") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTestConnection) { Text("Test connection") }
        }
        ConnectionStatusText(connection)

        HorizontalDivider()

        SectionTitle("Model")
        DropdownField(
            label = "Model",
            selected = settings.model.ifBlank { "Select a model" },
            options = models,
            trailing = {
                IconButton(onClick = onLoadModels) {
                    if (modelsLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh models")
                    }
                }
            },
            onSelect = { name -> onUpdate { it.copy(model = name) } },
        )

        HorizontalDivider()

        SectionTitle("System prompt")
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it; onUpdate { s -> s.copy(systemPrompt = it) } },
            label = { Text("System prompt") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        SectionTitle("Speech")
        SliderRow(
            label = "Speaking speed",
            value = settings.ttsSpeed,
            valueText = "${"%.1f".format(settings.ttsSpeed)}×",
            range = 0.5f..2.0f,
            steps = 14,
            onChange = { onUpdate { s -> s.copy(ttsSpeed = it) } },
        )
        val voiceOptions = listOf("System default") + voices.map { it.displayName }
        DropdownField(
            label = "Voice",
            selected = voices.firstOrNull { it.id == settings.ttsVoiceId }?.displayName ?: "System default",
            options = voiceOptions,
            onOpen = onLoadVoices,
            onSelect = { display ->
                val voiceId = voices.firstOrNull { it.displayName == display }?.id
                onUpdate { it.copy(ttsVoiceId = voiceId) }
            },
        )

        HorizontalDivider()

        SectionTitle("Conversation")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto-listen after each reply", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.autoListen,
                onCheckedChange = { onUpdate { s -> s.copy(autoListen = it) } },
            )
        }
        SliderRow(
            label = "Listening silence timeout",
            value = settings.listeningTimeoutMs.toFloat(),
            valueText = "${"%.1f".format(settings.listeningTimeoutMs / 1000f)}s",
            range = 1_000f..5_000f,
            steps = 7,
            onChange = { onUpdate { s -> s.copy(listeningTimeoutMs = it.roundToInt().toLong()) } },
        )

        Box(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ConnectionStatusText(connection: ConnectionUiState) {
    when (connection) {
        ConnectionUiState.Idle -> Unit
        ConnectionUiState.Testing -> Text("Testing…", style = MaterialTheme.typography.bodyMedium)
        is ConnectionUiState.Success -> Text(
            connection.message,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
        is ConnectionUiState.Failure -> Text(
            connection.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                expanded = it
                if (it) onOpen()
            },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (options.isEmpty()) {
                    DropdownMenuItem(text = { Text("No options — refresh") }, onClick = { expanded = false })
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        trailing?.invoke()
    }
}
