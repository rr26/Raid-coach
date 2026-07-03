package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val imagePath: String? = null,
    val timestamp: Long,
    val isAutoScreenshot: Boolean = false
)
