package com.raidcoach.app

import android.graphics.Bitmap

enum class CaptureMode { AUTO, ON_DEMAND }

data class ApiContentBlock(
    val type: String,
    val text: String? = null,
    val imageBase64: String? = null,
    val mediaType: String = "image/jpeg"
)

data class ApiMessage(
    val role: String,
    val blocks: List<ApiContentBlock>
)

data class DisplayEntry(
    val label: String,
    val text: String,
    val thumbnail: Bitmap? = null,
    val timestamp: Long = System.currentTimeMillis()
)
