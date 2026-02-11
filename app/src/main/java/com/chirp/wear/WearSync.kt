package com.chirp.wear

import android.content.Context
import com.chirp.data.TranscriptRepository
import com.chirp.realtime.SessionState
import com.chirp.realtime.VoiceSessionController
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WearSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessionController: VoiceSessionController,
    private val transcriptRepository: TranscriptRepository,
) {
    fun start() {
        scope.launch {
            sessionController.state.collectLatest { state ->
                sendStatus(state)
            }
        }
        scope.launch {
            transcriptRepository.streamAll()
                .map { it.lastOrNull() }
                .distinctUntilChangedBy { it?.text }
                .collectLatest { item ->
                    if (item != null) {
                        sendTranscript(item.text)
                    }
                }
        }
    }

    private suspend fun sendStatus(state: SessionState) {
        val payload = JSONObject().apply {
            put("status", state.message)
            put("live", state.isLive)
        }
        sendMessage(WearPaths.STATUS, payload.toString().toByteArray())
    }

    private suspend fun sendTranscript(text: String) {
        val payload = JSONObject().apply {
            put("text", text)
        }
        sendMessage(WearPaths.TRANSCRIPT, payload.toString().toByteArray())
    }

    private suspend fun sendMessage(path: String, data: ByteArray) {
        val nodes = withContext(Dispatchers.IO) {
            Wearable.getNodeClient(context).connectedNodes.await()
        }
        val client = Wearable.getMessageClient(context)
        for (node in nodes) {
            client.sendMessage(node.id, path, data).await()
        }
    }
}
