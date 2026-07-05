package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_priority")
data class DailyPriorityEntity(
    @PrimaryKey val id: Int = 0,
    val text: String,
    val timestamp: Long
)
