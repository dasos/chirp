package com.chirp.wear.tile

import android.content.Context
import android.content.Intent
import androidx.wear.tiles.Tile
import androidx.wear.tiles.action.ActionBuilders
import androidx.wear.tiles.layout.LayoutElementBuilders
import androidx.wear.tiles.request.RequestBuilders
import androidx.wear.tiles.TileService
import com.chirp.wear.WearMainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Quick-launch Chirp tile (PHASE 2): tap opens [WearMainActivity] with
 * [WearMainActivity.EXTRA_AUTO_START], which starts a new conversation (sending
 * `Listen` too when the phone's start-listening setting is on). The tile is
 * intentionally static for now; a full live-state tile would need a second Data
 * Layer subscription.
 */
class ChirpTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<Tile> =
        Futures.immediateFuture(buildTile(this))

    private fun buildTile(context: Context): Tile {
        val launch = Intent(context, WearMainActivity::class.java)
            .putExtra(WearMainActivity.EXTRA_AUTO_START, true)

        val clickable: LayoutElementBuilders.ClickableElement =
            LayoutElementBuilders.ClickableElement.builder()
                .setClickablePageId("tap_to_start")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder().setLaunchActivity(launch).build()
                )
                .setContent(tapContent())
                .build()

        return Tile.Builder()
            .setTileId("chirp_tile")
            .setLayout(
                LayoutElementBuilders.BoxLayout.builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(clickable)
                    .build(),
            )
            .build()
    }

    private fun tapContent(): LayoutElementBuilders.LayoutElement {
        val label = LayoutElementBuilders.TextLayout.builder()
            .setText("Chirp")
            .build()
        val hint = LayoutElementBuilders.TextLayout.builder()
            .setText("Tap to talk")
            .build()
        return LayoutElementBuilders.FlowLayout.builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(label)
            .addContent(hint)
            .build()
    }
}