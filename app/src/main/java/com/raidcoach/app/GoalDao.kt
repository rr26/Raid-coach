package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE topic = :topic ORDER BY createdAt ASC")
    suspend fun getByTopic(topic: String): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE status = 'active' ORDER BY createdAt ASC")
    suspend fun getAllActive(): List<GoalEntity>

    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("UPDATE goals SET topic = :newTopic WHERE topic = :oldTopic")
    suspend fun renameTopic(oldTopic: String, newTopic: String)

    @Query("DELETE FROM goals WHERE topic = :topic")
    suspend fun deleteByTopic(topic: String)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: Long)
}
