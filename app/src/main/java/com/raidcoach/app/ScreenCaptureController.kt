package com.raidcoach.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class ScreenCaptureController(
    context: Context,
    private val mediaProjection: MediaProjection
) {

    private val displayMetrics = context.resources.displayMetrics
    private val width = displayMetrics.widthPixels
    private val height = displayMetrics.heightPixels
    private val density = displayMetrics.densityDpi

    private val handlerThread = HandlerThread("RaidCoachCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    @Volatile
    private var pendingCapture: CompletableDeferred<Bitmap?>? = null

    private val virtualDisplay: VirtualDisplay = mediaProjection.createVirtualDisplay(
        "raid-coach-capture",
        width,
        height,
        density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader.surface,
        null,
        handler
    )

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) return@setOnImageAvailableListener

            val deferred = pendingCapture
            if (deferred != null && !deferred.isCompleted) {
                pendingCapture = null
                deferred.complete(imageToBitmap(image))
            }
            image.close()
        }, handler)
    }

    suspend fun captureBitmap(timeoutMs: Long = 3000L): Bitmap? {
        val deferred = CompletableDeferred<Bitmap?>()
        pendingCapture = deferred
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    fun release() {
        pendingCapture?.complete(null)
        pendingCapture = null
        runCatching { virtualDisplay.release() }
        runCatching { imageReader.close() }
        runCatching { handlerThread.quitSafely() }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }
}
