package com.raidcoach.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        ChampionCacheEntity::class,
        TopicEntity::class,
        TopicSummaryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun championCacheDao(): ChampionCacheDao
    abstract fun topicDao(): TopicDao
    abstract fun topicSummaryDao(): TopicSummaryDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "raid_coach_chat.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
