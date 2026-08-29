package com.chirp.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.chirp.core.session.SessionCommand
import com.chirp.wear.data.WearCommandClient
import com.chirp.wear.ui.ChirpWearTheme
import com.chirp.wear.ui.WearScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Thin Wear remote: renders the phone-mirrored [com.chirp.core.session.SessionState]
 * and sends [SessionCommand]s back via [WearCommandClient]. All audio stays on
 * the phone (the headset must be paired to the phone, not the watch).
 */
class WearMainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: WearStateRepository
    private val client by lazy { WearCommandClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WearStateRepository(applicationContext)
        repo.start()

        // Tile tap launches us with EXTRA_AUTO_START: run the start flow as if
        // the big button were pressed (Start, then Listen if the setting is on).
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            autoStartIfIdle()
        }

        setContent {
            ChirpWearTheme {
                val state by repo.state.collectAsState()
                WearScreen(
                    state = state,
                    onBigButton = ::onBigButton,
                )
            }
        }
    }

    private fun autoStartIfIdle() {
        scope.launch {
            // Let the first synced state arrive (the listener may not have
            // delivered before we check).
            client.send(SessionCommand.Start(null))
            if (repo.startListeningOnNewConversation.value) {
                client.send(SessionCommand.StartListening)
            }
        }
    }

    private fun onBigButton() {
        val session = repo.state.value
        if (session?.active == true) {
            scope.launch { client.send(SessionCommand.PressPrimary) }
        } else {
            scope.launch {
                client.send(SessionCommand.Start(null))
                if (repo.startListeningOnNewConversation.value) {
                    client.send(SessionCommand.StartListening)
                }
            }
        }
    }

    override fun onDestroy() {
        repo.stop()
        super.onDestroy()
    }

    companion object {
        /** Set by the tile tap to trigger the start flow on launch. */
        const val EXTRA_AUTO_START = "auto_start"
    }
}