package com.chirp.wear

import android.content.Context
import android.util.Log
import com.chirp.data.TranscriptRepository
import com.chirp.realtime.SessionState
import com.chirp.realtime.VoiceSessionController
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
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
    private companion object {
        private const val TAG = "WearSync"
    }
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
        val nodes = try {
            withContext(Dispatchers.IO) {
                Wearable.getNodeClient(context).connectedNodes.await()
            }
        } catch (e: ApiException) {
            if (e.statusCode == CommonStatusCodes.API_NOT_CONNECTED ||
                e.statusCode == CommonStatusCodes.API_UNAVAILABLE
            ) {
                Log.i(TAG, "Wearable API unavailable on this device.")
                return
            }
            Log.w(TAG, "Wearable node lookup failed.", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Wearable node lookup failed.", e)
            return
        }

        val client = Wearable.getMessageClient(context)
        for (node in nodes) {
            try {
                client.sendMessage(node.id, path, data).await()
            } catch (e: ApiException) {
                if (e.statusCode == CommonStatusCodes.API_NOT_CONNECTED ||
                    e.statusCode == CommonStatusCodes.API_UNAVAILABLE
                ) {
                    Log.i(TAG, "Wearable API unavailable on this device.")
                    return
                }
                Log.w(TAG, "Wearable send failed.", e)
            } catch (e: Exception) {
                Log.w(TAG, "Wearable send failed.", e)
            }
        }
    }
}
