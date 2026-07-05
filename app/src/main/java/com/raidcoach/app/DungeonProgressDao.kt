package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DungeonProgressDao {
    @Query("SELECT * FROM dungeon_progress")
    suspend fun getAll(): List<DungeonProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DungeonProgressEntity)
}
