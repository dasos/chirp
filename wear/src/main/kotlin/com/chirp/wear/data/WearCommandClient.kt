package com.chirp.wear.data

import android.content.Context
import android.util.Log
import com.chirp.core.session.SessionCommand
import com.chirp.core.wear.WearContract
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Watch-side sender: delivers [SessionCommand]s to the phone over the
 * `/chirp/command` MessageClient path, resolved via the
 * [WearContract.CAPABILITY_PHONE_APP] capability.
 */
class WearCommandClient(context: Context) {

    private val context: Context = context.applicationContext

    private suspend fun phoneNodes(): Set<Node> =
        Wearable.getCapabilityClient(context)
            .getCapability(WearContract.CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes

    private suspend fun phoneNode(): Node? {
        val nodes = phoneNodes()
        Log.d(TAG, "phoneNodes for capability ${WearContract.CAPABILITY_PHONE_APP}: $nodes")
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    /** Sends a command to the phone; returns false if no phone node is reachable. */
    suspend fun send(command: SessionCommand): Boolean {
        val node = phoneNode()
        if (node == null) {
            Log.w(TAG, "no phone node advertising ${WearContract.CAPABILITY_PHONE_APP}; dropping $command")
            return false
        }
        return try {
            Wearable.getMessageClient(context)
                .sendMessage(node.id, WearContract.PATH_COMMAND, WearContract.encodeCommand(command))
                .await()
            Log.d(TAG, "sent $command -> ${node.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed for $command", e)
            false
        }
    }

    companion object {
        private const val TAG = "ChirpWear"
    }
}