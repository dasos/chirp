package com.chirp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chirp.service.VoiceSessionService
import com.chirp.ui.ChirpTheme
import com.chirp.ui.MainScreen
import com.chirp.ui.MainViewModel
import com.chirp.ui.MainViewModelFactory

class MainActivity : ComponentActivity() {
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModelFactory((application as ChirpApp).container),
            )

            ChirpTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        viewModel = viewModel,
                        onStartSession = { startSessionService() },
                        onStopSession = { stopSessionService() },
                        onRequestMic = { requestPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
                    )
                }
            }
        }
    }

    private fun startSessionService() {
        val intent = Intent(this, VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopSessionService() {
        val intent = Intent(this, VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_STOP
        }
        startService(intent)
    }
}
