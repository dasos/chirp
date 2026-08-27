package com.chirp

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    var navigateToSettings by remember { mutableStateOf(false) }

    val needsOnboarding = settings != null &&
        !onboardingDismissed &&
        (settings!!.apiKey.isBlank() || settings!!.model.isBlank())

    if (needsOnboarding) {
        navigateToSettings = false
        OnboardingScreen(
            settingsRepository = settingsRepository,
            chatClient = chatClient,
            onComplete = { goToSettings ->
                navigateToSettings = goToSettings
                onboardingDismissed = true
            },
        )
    } else if (settings != null) {
        ChirpNavHost(
            startDestination = if (navigateToSettings) Routes.SETTINGS else Routes.HOME,
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
