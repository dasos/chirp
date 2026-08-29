package com.chirp.wear.data

import android.content.Context
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
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    /** Sends a command to the phone; returns false if no phone node is reachable. */
    suspend fun send(command: SessionCommand): Boolean {
        val node = phoneNode() ?: return false
        Wearable.getMessageClient(context)
            .sendMessage(node.id, WearContract.PATH_COMMAND, WearContract.encodeCommand(command))
            .await()
        return true
    }
}