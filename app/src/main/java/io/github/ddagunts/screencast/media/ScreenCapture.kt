package io.github.ddagunts.screencast.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import io.github.ddagunts.screencast.util.logI

class ScreenCapture(private val context: Context) {
    data class Size(val width: Int, val height: Int, val dpi: Int)

    private var virtualDisplay: VirtualDisplay? = null

    fun start(projection: MediaProjection, surface: Surface, size: Size) {
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCast",
            size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
        logI("VirtualDisplay created ${size.width}x${size.height}")
    }

    fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
    }

    companion object {
        // Chromecast's Default Media Receiver expects landscape 16:9. The physical (portrait)
        // display is letterboxed inside this surface; this is the same approach Google's Cast
        // sender uses for screen mirroring.
        fun sizeFor(context: Context, resolution: Resolution): Size {
            return Size(resolution.width, resolution.height, context.resources.configuration.densityDpi)
        }
    }
}
