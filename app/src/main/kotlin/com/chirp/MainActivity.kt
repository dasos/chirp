package com.chirp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.chirp.core.chat.ChatClient
import com.chirp.data.settings.SettingsRepository
import com.chirp.ui.navigation.ChirpNavHost
import com.chirp.ui.navigation.Routes
import com.chirp.ui.onboarding.OnboardingScreen
import com.chirp.ui.settings.SettingsScreen
import com.chirp.ui.permissions.RequestNotificationPermission
import com.chirp.ui.theme.ChirpTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chatClient: ChatClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChirpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RequestNotificationPermission()
                    OnboardingGate(
                        settingsRepository = settingsRepository,
                        chatClient = chatClient,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingGate(
    settingsRepository: SettingsRepository,
    chatClient: ChatClient,
) {
    val settings by settingsRepository.settings.collectAsState(initial = null)
    var onboardingDismissed by remember { mutableStateOf(false) }
    var initialSettingsObserved by remember { mutableStateOf(false) }
    var navigateToSettings by remember { mutableStateOf(false) }
    // True when the welcome screen's "custom endpoint / settings" link is open.
    // Settings is shown as an overlay (rather than a nav destination) so the back
    // button returns to the in-progress onboarding flow.
    var settingsOverlay by remember { mutableStateOf(false) }

    // On a fresh launch, skip onboarding when setup was already completed. During
    // the current setup flow, however, keep it visible after page 1 saves the model
    // so the final Done page can still be shown.
    androidx.compose.runtime.LaunchedEffect(settings) {
        if (!initialSettingsObserved && settings != null) {
            onboardingDismissed = settings!!.apiKey.isNotBlank() && settings!!.model.isNotBlank()
            initialSettingsObserved = true
        }
    }

    val showOnboarding = settings != null && !onboardingDismissed

    Box(Modifier.fillMaxSize()) {
        when {
            // Wait until the first settings value has been observed before
            // selecting the initial screen. This prevents a configured user from
            // seeing onboarding for one composition during app startup.
            !initialSettingsObserved -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            showOnboarding -> {
                navigateToSettings = false
                OnboardingScreen(
                    settingsRepository = settingsRepository,
                    chatClient = chatClient,
                    onComplete = { goToSettings ->
                        navigateToSettings = goToSettings
                        onboardingDismissed = true
                    },
                    onOpenSettings = { settingsOverlay = true },
                )
            }
            settings != null -> {
                ChirpNavHost(
                    startDestination = Routes.HOME,
                    startInSettings = navigateToSettings,
                )
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (settingsOverlay) {
            BackHandler { settingsOverlay = false }
            SettingsScreen(onBack = { settingsOverlay = false })
        }
    }
}
