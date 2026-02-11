package com.chirp.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class WearCommandClient(private val context: Context) {
    suspend fun sendStart() = sendMessage(PATH_START)

    suspend fun sendStop() = sendMessage(PATH_STOP)

    private suspend fun sendMessage(path: String) {
        withContext(Dispatchers.IO) {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val messageClient = Wearable.getMessageClient(context)
            for (node in nodes) {
                messageClient.sendMessage(node.id, path, ByteArray(0)).await()
            }
        }
    }

    companion object {
        const val PATH_START = WearPaths.START
        const val PATH_STOP = WearPaths.STOP
    }
}
