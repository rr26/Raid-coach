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
        TopicSummaryEntity::class,
        DungeonProgressEntity::class,
        SavedTeamEntity::class,
        AccountMetaEntity::class,
        GoalEntity::class,
        DailyPriorityEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun championCacheDao(): ChampionCacheDao
    abstract fun topicDao(): TopicDao
    abstract fun topicSummaryDao(): TopicSummaryDao
    abstract fun dungeonProgressDao(): DungeonProgressDao
    abstract fun savedTeamDao(): SavedTeamDao
    abstract fun accountMetaDao(): AccountMetaDao
    abstract fun goalDao(): GoalDao
    abstract fun dailyPriorityDao(): DailyPriorityDao

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
