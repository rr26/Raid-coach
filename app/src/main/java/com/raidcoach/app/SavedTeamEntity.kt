package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_teams")
data class SavedTeamEntity(
    @PrimaryKey val topic: String,
    val teamNotes: String,
    val lastUpdated: Long
)
