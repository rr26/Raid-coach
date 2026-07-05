package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "topic_summaries")
data class TopicSummaryEntity(
    @PrimaryKey val topic: String,
    val summary: String,
    val timestamp: Long
)
