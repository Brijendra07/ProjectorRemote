package com.brijendra.projectorremote.media

import android.content.Context
import android.net.Uri
import com.brijendra.projectorremote.adb.Connector
import com.brijendra.projectorremote.adb.HelperClient
import com.brijendra.projectorremote.adb.RemoteController
import com.brijendra.projectorremote.service.RemoteService

/** Sends phone videos/photos to the projector. Returns null on success, else an error. */
object Caster {

    suspend fun cast(ctx: Context, uris: List<Uri>, video: Boolean): String? {
        if (uris.isEmpty()) return "Nothing selected"
        if (!Connector.ensure(ctx)) return "Projector not connected"
        val urls = uris.mapNotNull { MediaHub.publish(ctx, it) }
        if (urls.isEmpty()) return "Connect your phone to the same Wi-Fi as the projector"

        // Keep the app alive in the background while the projector streams from the phone.
        RemoteService.start(ctx)

        val remote = RemoteController.get(ctx)
        val helper = HelperClient.query(Connector.savedHost(ctx))
        val out = if (helper != null && helper.version >= 4) {
            val type = if (video) "video" else "image"
            remote.shellOutput(
                "am start -n ${HelperClient.PACKAGE}/.PlayerActivity " +
                    "--es type $type --ei index 0 --esa urls '${urls.joinToString(",")}'"
            )
        } else {
            val mime = if (video) "video/*" else "image/*"
            remote.shellOutput("am start -a android.intent.action.VIEW -d '${urls.first()}' -t '$mime'")
        }
        return if (out.contains("Error")) "The projector couldn't open this file" else null
    }
}
