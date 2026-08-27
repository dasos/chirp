package com.chirp.ui.onboarding

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chirp.core.chat.ChatClient
import com.chirp.core.chat.ChatModel
import com.chirp.data.settings.SettingsRepository
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3
private const val MAX_MODELS_SHOWN = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    settingsRepository: SettingsRepository,
    chatClient: ChatClient,
    onComplete: (goToSettings: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val savedSettings by settingsRepository.settings.collectAsState(initial = null)

    var currentPage by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<ChatModel?>(null) }
    var models by remember { mutableStateOf<List<ChatModel>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var manualModelId by remember { mutableStateOf("") }
    var isManualEntry by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(savedSettings) {
        if (apiKey.isBlank()) apiKey = savedSettings?.apiKey.orEmpty()
    }

    LaunchedEffect(currentPage) {
        pagerState.animateScrollToPage(currentPage)
    }
    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            // Top area — page title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> ApiKeyPage(
                            apiKey = apiKey,
                            showApiKey = showApiKey,
                            onApiKeyChange = { apiKey = it },
                            onToggleShow = { showApiKey = !showApiKey },
                            onOpenSettings = onOpenSettings,
                        )
                        1 -> ModelPage(
                            selectedModel = selectedModel,
                            models = models,
                            modelsLoading = modelsLoading,
                            modelsError = modelsError,
                            isManualEntry = isManualEntry,
                            manualModelId = manualModelId,
                            onSelectModel = { selectedModel = it; isManualEntry = false },
                            onManualModelChange = { manualModelId = it },
                            onToggleManual = { isManualEntry = !isManualEntry },
                            onRefresh = {
                                scope.launch {
                                    modelsLoading = true
                                    modelsError = null
                                    try {
                                        models = chatClient.listModels()
                                    } catch (e: Exception) {
                                        modelsError = e.message ?: "Could not load models"
                                    } finally {
                                        modelsLoading = false
                                    }
                                }
                            },
                        )
                        2 -> DonePage()
                    }
                }
            }

            // Page indicator dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(PAGE_COUNT) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .then(
                                if (i == currentPage) {
                                    Modifier.size(10.dp)
                                } else {
                                    Modifier.size(8.dp)
                                }
                            ),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            color = if (i == currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ) {}
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Navigation buttons
            when (currentPage) {
                0 -> {
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                settingsRepository.update { it.copy(apiKey = apiKey) }
                                saving = false
                                currentPage = 1
                            }
                        },
                        enabled = apiKey.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Next")
                        }
                    }
                }
                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { currentPage = 0 },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Back")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    val modelId = if (isManualEntry) manualModelId else selectedModel?.id.orEmpty()
                                    settingsRepository.update { it.copy(model = modelId) }
                                    currentPage = 2
                                }
                            },
                            enabled = (selectedModel != null || (isManualEntry && manualModelId.isNotBlank())),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Next")
                        }
                    }
                }
                2 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onComplete(true) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("More Settings")
                        }
                        Button(
                            onClick = { onComplete(false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyPage(
    apiKey: String,
    showApiKey: Boolean,
    onApiKeyChange: (String) -> Unit,
    onToggleShow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Welcome to Chirp",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Hands-free voice conversations with AI.\nSpeak naturally, get spoken answers — just like a phone call.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("OpenRouter API key") },
            placeholder = { Text("sk-or-…") },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleShow) {
                    Text(if (showApiKey) "Hide" else "Show")
                }
            },
            supportingText = {
                Text("Get your key at openrouter.ai/keys.")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Use a custom endpoint or other settings")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPage(
    selectedModel: ChatModel?,
    models: List<ChatModel>,
    modelsLoading: Boolean,
    modelsError: String?,
    isManualEntry: Boolean,
    manualModelId: String,
    onSelectModel: (ChatModel) -> Unit,
    onManualModelChange: (String) -> Unit,
    onToggleManual: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "Choose a model",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Select an AI model for your conversations. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        if (isManualEntry) {
            OutlinedTextField(
                value = manualModelId,
                onValueChange = onManualModelChange,
                label = { Text("Model ID") },
                placeholder = { Text("e.g. anthropic/claude-sonnet-4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onToggleManual) {
                Text("Browse available models")
            }
        } else {
            var query by remember { mutableStateOf("") }
            var active by remember { mutableStateOf(false) }

            LaunchedEffect(active, models, modelsLoading) {
                if (active && models.isEmpty() && !modelsLoading) onRefresh()
            }

            val filtered = remember(query, models) {
                val q = query.trim()
                if (q.isEmpty()) models.take(MAX_MODELS_SHOWN)
                else models.filter {
                    it.id.contains(q, ignoreCase = true) ||
                        it.label?.contains(q, ignoreCase = true) == true
                }.take(MAX_MODELS_SHOWN)
            }

            DockedSearchBar(
                query = if (active) query else selectedModel?.let { it.label ?: it.id }.orEmpty(),
                onQueryChange = { query = it },
                onSearch = {},
                active = active,
                onActiveChange = {
                    active = it
                    if (!it) query = ""
                },
                placeholder = { Text(if (selectedModel == null) "Select a model" else "Search models") },
                trailingIcon = {
                    IconButton(onClick = onRefresh) {
                        if (modelsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Refresh models",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (modelsLoading) {
                    ListItem(
                        headlineContent = { Text("Loading models…") },
                    )
                } else if (modelsError != null) {
                    ListItem(
                        headlineContent = { Text("Could not load models") },
                        supportingContent = { Text(modelsError) },
                    )
                } else if (filtered.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("No models found") },
                    )
                } else {
                    filtered.forEach { model ->
                        val label = model.label
                        ListItem(
                            headlineContent = { Text(label ?: model.id) },
                            supportingContent = if (label != null && label != model.id) {
                                { Text(model.id) }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable {
                                onSelectModel(model)
                                active = false
                                query = ""
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (modelsError != null) {
                TextButton(onClick = onToggleManual) {
                    Text("Or enter a model ID manually")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (selectedModel != null) {
                Text(
                    "Selected: ${selectedModel.label ?: selectedModel.id}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DonePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "You're all set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Your API key and model are configured.\nTap Start to begin your first conversation.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
    }
}