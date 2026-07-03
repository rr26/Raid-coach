package com.raidcoach.app

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
