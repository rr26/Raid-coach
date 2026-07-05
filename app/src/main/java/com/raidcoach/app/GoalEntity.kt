package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

object GoalStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val DONE = "done"
}

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val topic: String,
    val status: String = GoalStatus.ACTIVE,
    val createdAt: Long,
    val plan: String = ""
)
