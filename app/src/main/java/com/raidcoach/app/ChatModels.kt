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
    val timestamp: Long = System.currentTimeMillis(),
    val isTyping: Boolean = false,
    val isWatching: Boolean = false,
    val isError: Boolean = false,
    val usedWebSearch: Boolean = false
)

data class AnthropicReply(
    val text: String,
    val usedWebSearch: Boolean
)
