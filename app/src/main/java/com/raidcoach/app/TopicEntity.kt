package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "topics")
data class TopicEntity(
    @PrimaryKey val name: String,
    val sortOrder: Int
)
