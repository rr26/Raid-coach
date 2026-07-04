package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "champion_cache")
data class ChampionCacheEntity(
    @PrimaryKey val championName: String,
    val summary: String,
    val timestamp: Long
)
