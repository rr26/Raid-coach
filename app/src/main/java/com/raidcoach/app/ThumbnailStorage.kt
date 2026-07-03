package com.raidcoach.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object ThumbnailStorage {

    private const val DIR_NAME = "thumbnails"
    private const val JPEG_QUALITY = 80

    fun save(context: Context, bitmap: Bitmap, timestamp: Long): String {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "thumb_${timestamp}_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        return file.absolutePath
    }

    fun load(path: String): Bitmap? = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    fun clearAll(context: Context) {
        File(context.filesDir, DIR_NAME).listFiles()?.forEach { runCatching { it.delete() } }
    }
}
