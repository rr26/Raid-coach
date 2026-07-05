package com.raidcoach.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account_meta")
data class AccountMetaEntity(
    @PrimaryKey val id: Int = 0,
    val playerLevel: String,
    val focusAreas: String,
    val notes: String,
    val lastUpdated: Long
)
