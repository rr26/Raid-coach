package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyPriorityDao {
    @Query("SELECT * FROM daily_priority WHERE id = 0")
    suspend fun get(): DailyPriorityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyPriorityEntity)
}
