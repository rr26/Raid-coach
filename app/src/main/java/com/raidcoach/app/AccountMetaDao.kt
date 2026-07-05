package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountMetaDao {
    @Query("SELECT * FROM account_meta WHERE id = 0")
    suspend fun get(): AccountMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccountMetaEntity)
}
