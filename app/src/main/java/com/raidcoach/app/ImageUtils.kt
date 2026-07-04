package com.raidcoach.app

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

private const val MAX_LONG_EDGE_PX = 1568
private const val JPEG_QUALITY = 80
private const val THUMBNAIL_SIZE = 32
private const val NEAR_DUPLICATE_THRESHOLD = 8

fun downscaleForUpload(bitmap: Bitmap): Bitmap {
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= MAX_LONG_EDGE_PX) return bitmap

    val scale = MAX_LONG_EDGE_PX.toFloat() / longEdge
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

fun isNearlyIdentical(a: Bitmap, b: Bitmap): Boolean {
    val thumbA = Bitmap.createScaledBitmap(a, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
    val thumbB = Bitmap.createScaledBitmap(b, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)

    val pixelsA = IntArray(THUMBNAIL_SIZE * THUMBNAIL_SIZE)
    val pixelsB = IntArray(THUMBNAIL_SIZE * THUMBNAIL_SIZE)
    thumbA.getPixels(pixelsA, 0, THUMBNAIL_SIZE, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
    thumbB.getPixels(pixelsB, 0, THUMBNAIL_SIZE, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
    thumbA.recycle()
    thumbB.recycle()

    var diffSum = 0L
    for (i in pixelsA.indices) {
        diffSum += channelDiff(pixelsA[i], pixelsB[i])
    }

    val avgDiff = diffSum / pixelsA.size
    return avgDiff < NEAR_DUPLICATE_THRESHOLD
}

private fun channelDiff(pixelA: Int, pixelB: Int): Int {
    val rDiff = abs(Color.red(pixelA) - Color.red(pixelB))
    val gDiff = abs(Color.green(pixelA) - Color.green(pixelB))
    val bDiff = abs(Color.blue(pixelA) - Color.blue(pixelB))
    return (rDiff + gDiff + bDiff) / 3
}
