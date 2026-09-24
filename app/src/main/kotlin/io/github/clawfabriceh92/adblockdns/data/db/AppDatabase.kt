package io.github.clawfabriceh92.adblockdns.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BlockedEventEntity::class, RuleEntity::class, ListStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedEventDao(): BlockedEventDao
    abstract fun ruleDao(): RuleDao
    abstract fun listStateDao(): ListStateDao

    companion object {
        const val FILE_NAME = "adblock.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME).build()
    }
}
