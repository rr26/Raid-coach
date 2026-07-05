package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SavedTeamDao {
    @Query("SELECT * FROM saved_teams")
    suspend fun getAll(): List<SavedTeamEntity>

    @Query("SELECT * FROM saved_teams WHERE topic = :topic")
    suspend fun get(topic: String): SavedTeamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedTeamEntity)

    @Query("UPDATE saved_teams SET topic = :newTopic WHERE topic = :oldTopic")
    suspend fun renameTopic(oldTopic: String, newTopic: String)

    @Query("DELETE FROM saved_teams WHERE topic = :topic")
    suspend fun delete(topic: String)
}
