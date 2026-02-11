package com.chirp.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Scaffold
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WearStateStore(this)
        val commands = WearCommandClient(this)

        setContent {
            MaterialTheme {
                WearApp(store = store, commands = commands)
            }
        }
    }
}

@Composable
private fun WearApp(store: WearStateStore, commands: WearCommandClient) {
    val state by store.state().collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = if (state.isLive) "Live" else "Idle",
                    style = MaterialTheme.typography.title2,
                )
            }
            item {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Button(onClick = {
                    scope.launch { commands.sendStart() }
                }) {
                    Text("Start")
                }
            }
            item {
                Button(onClick = {
                    scope.launch { commands.sendStop() }
                }) {
                    Text("Stop")
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Last heard", style = MaterialTheme.typography.caption2)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (state.lastTranscript.isBlank()) "—" else state.lastTranscript,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
