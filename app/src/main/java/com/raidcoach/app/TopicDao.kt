package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TopicDao {
    @Query("SELECT * FROM topics")
    suspend fun getAll(): List<TopicEntity>

    @Insert
    suspend fun insert(topic: TopicEntity)

    @Insert
    suspend fun insertAll(topics: List<TopicEntity>)

    @Query("UPDATE topics SET name = :newName WHERE name = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("DELETE FROM topics WHERE name = :name")
    suspend fun delete(name: String)
}
