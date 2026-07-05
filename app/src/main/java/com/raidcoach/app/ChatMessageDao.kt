package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE topic = :topic ORDER BY timestamp ASC")
    suspend fun getByTopic(topic: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE topic = :topic")
    suspend fun deleteByTopic(topic: String)

    @Query("UPDATE chat_messages SET topic = :newTopic WHERE topic = :oldTopic")
    suspend fun renameTopic(oldTopic: String, newTopic: String)
}
