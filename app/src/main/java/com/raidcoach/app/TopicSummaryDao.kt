package com.raidcoach.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TopicSummaryDao {
    @Query("SELECT * FROM topic_summaries WHERE topic = :topic")
    suspend fun get(topic: String): TopicSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TopicSummaryEntity)

    @Query("UPDATE topic_summaries SET topic = :newTopic WHERE topic = :oldTopic")
    suspend fun renameTopic(oldTopic: String, newTopic: String)

    @Query("DELETE FROM topic_summaries WHERE topic = :topic")
    suspend fun delete(topic: String)
}
