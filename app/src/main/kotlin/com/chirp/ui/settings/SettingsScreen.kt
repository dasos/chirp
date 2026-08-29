package com.chirp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import com.chirp.ui.permissions.hasCoarseLocationPermission
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import com.chirp.core.chat.ChatModel
import com.chirp.data.settings.AppSettings
import kotlin.math.roundToInt

/** Cap on suggestion rows rendered by the model search field. */
private const val MAX_MODELS_SHOWN = 100

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
            models = models,
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
    models: List<ChatModel>,
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
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!seeded) {
            apiKey = settings.apiKey
            baseUrl = settings.baseUrl
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
        SectionTitle("OpenRouter / API Connection")
        Text(
            "Chirp connects to OpenRouter by default. Enter your OpenRouter API key below, or change the Base URL for a different provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; onUpdate { s -> s.copy(apiKey = it) } },
            label = { Text("API key") },
            placeholder = { Text("sk-or-…") },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showApiKey = !showApiKey }) {
                    Text(if (showApiKey) "Hide" else "Show")
                }
            },
            supportingText = { Text("Sent as a bearer token; stored encrypted.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTestConnection) { Text("Test connection") }
            if (settings.model.isBlank()) {
                Text(
                    "Select a model first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        ConnectionStatusText(connection)

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; onUpdate { s -> s.copy(baseUrl = it) } },
            label = { Text("API base URL") },
            supportingText = { Text("Any OpenAI-compatible endpoint. Defaults to OpenRouter. Use https:// for remote hosts.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        SectionTitle("Model")
        ModelSearchField(
            selected = models.firstOrNull { it.id == settings.model },
            models = models,
            modelsLoading = modelsLoading,
            onLoadModels = onLoadModels,
            onSelect = { onUpdate { s -> s.copy(model = it.id) } },
        )
        if (settings.model.isBlank()) {
            Text(
                "Select a model before starting a conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Web search", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Let the model search the web when needed (extra cost per search)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.webSearch,
                onCheckedChange = { onUpdate { s -> s.copy(webSearch = it) } },
            )
        }

        HorizontalDivider()

        SectionTitle("Context & System Prompt")
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it; onUpdate { s -> s.copy(systemPrompt = it) } },
            label = { Text("System prompt") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Include current date & time", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Provides device date, time, and timezone to the model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.includeDateTime,
                onCheckedChange = { onUpdate { s -> s.copy(includeDateTime = it) } },
            )
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            onUpdate { s -> s.copy(includeLocation = isGranted) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Include approximate location", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Provides general city/region context to help with local queries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.includeLocation,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (context.hasCoarseLocationPermission()) {
                            onUpdate { s -> s.copy(includeLocation = true) }
                        } else {
                            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    } else {
                        onUpdate { s -> s.copy(includeLocation = false) }
                    }
                },
            )
        }

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
            Column(modifier = Modifier.weight(1f)) {
                Text("Start listening on new conversation", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Begin listening immediately after tapping New conversation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.startListeningOnNewConversation,
                onCheckedChange = { onUpdate { s -> s.copy(startListeningOnNewConversation = it) } },
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
private fun ModelSearchField(
    selected: ChatModel?,
    models: List<ChatModel>,
    modelsLoading: Boolean,
    onLoadModels: () -> Unit,
    onSelect: (ChatModel) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(active, models, modelsLoading) {
        if (active && models.isEmpty() && !modelsLoading) onLoadModels()
    }

    val filtered = remember(query, models) {
        val q = query.trim()
        val matches = if (q.isEmpty()) {
            models
        } else {
            models.filter {
                it.id.contains(q, ignoreCase = true) ||
                    it.label?.contains(q, ignoreCase = true) == true
            }
        }
        matches.take(MAX_MODELS_SHOWN)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Show the current selection when collapsed; the raw query while searching.
        val fieldText = if (active) query else selected?.let { it.label ?: it.id }.orEmpty()
        DockedSearchBar(
            query = fieldText,
            onQueryChange = { query = it },
            onSearch = {},
            active = active,
            onActiveChange = {
                active = it
                if (!it) query = ""
            },
            placeholder = { Text(if (selected == null) "Select a model" else "Search models") },
            trailingIcon = {
                IconButton(onClick = onLoadModels) {
                    if (modelsLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh models")
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(filtered, key = { it.id }) { model ->
                    val label = model.label
                    ListItem(
                        headlineContent = { Text(label ?: model.id) },
                        supportingContent = if (label != null && label != model.id) {
                            { Text(model.id) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable {
                            onSelect(model)
                            active = false
                            query = ""
                        },
                    )
                }
            }
        }
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
