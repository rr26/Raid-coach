package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dungeon_progress")
data class DungeonProgressEntity(
    @PrimaryKey val dungeon: String,
    val highestStageBeaten: String,
    val farmableStage: String,
    val avgRunTime: String,
    val lastUpdated: Long
)
