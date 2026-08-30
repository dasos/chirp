package com.chirp.wear.tile

import android.content.Context
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.chirp.wear.R
import com.chirp.wear.WearMainActivity
import com.google.common.util.concurrent.ListenableFuture

/**
 * Quick-launch Chirp tile (PHASE 2): tap opens [WearMainActivity] with
 * [WearMainActivity.EXTRA_AUTO_START], which starts a new conversation (sending
 * `Listen` too when the phone's start-listening setting is on). The tile is
 * intentionally static for now; a full live-state tile would need a second Data
 * Layer subscription.
 *
 * Built against the tiles 1.4.0 / protolayout 1.2.0 API: layout elements live in
 * `androidx.wear.protolayout`, and the tile itself is a `TileBuilders.Tile` with
 * a `Timeline`. The launch action carries the auto-start flag as an Android
 * boolean extra (the 1.4.0 `LaunchAction` has no Intent form).
 */
class ChirpTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tile = buildTile(this)
        return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                LOGO_RESOURCE_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(R.drawable.ic_launcher_foreground)
                            .build(),
                    )
                    .build(),
            )
            .build()
        return ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(resources) }
    }

    private fun buildTile(context: Context): TileBuilders.Tile {
        val launch = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(WearMainActivity::class.java.name)
            .addKeyToExtraMapping(
                WearMainActivity.EXTRA_AUTO_START,
                ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build(),
            )
            .build()

        val clickable = ModifiersBuilders.Clickable.Builder()
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(launch).build())
            .build()

        val logo = LayoutElementBuilders.Image.Builder()
            .setWidth(dp(48f))
            .setHeight(dp(48f))
            .setResourceId(LOGO_RESOURCE_ID)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription("Chirp")
                            .build(),
                    )
                    .build(),
            )
            .build()

        val content = LayoutElementBuilders.Column.Builder()
            .addContent(logo)
            .addContent(LayoutElementBuilders.Text.Builder().setText("Tap to talk").build())
            .build()

        val box = LayoutElementBuilders.Box.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build())
            .addContent(content)
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(box))
            .build()
    }

    private companion object {
        const val LOGO_RESOURCE_ID = "chirp_logo"
        const val RESOURCES_VERSION = "2"
    }
}