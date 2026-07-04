package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChampionCacheDao {
    @Query("SELECT * FROM champion_cache")
    suspend fun getAll(): List<ChampionCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChampionCacheEntity)
}
