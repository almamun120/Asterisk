// FILE: ./app/src/main/java/com/asterisk/voip/data/database/AsteriskDatabase.kt
package com.asterisk.voip.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.asterisk.voip.data.model.*

@Database(
    entities = [
        Extension::class,
        DialPlan::class,
        OfflineMessage::class,
        VoicePrompt::class,
        DialPlanRule::class,
        DialPlanSchedule::class,
        Registration::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AsteriskDatabase : RoomDatabase() {

    abstract fun extensionDao(): ExtensionDao
    abstract fun dialPlanDao(): DialPlanDao
    abstract fun offlineMessageDao(): OfflineMessageDao
    abstract fun voicePromptDao(): VoicePromptDao
    abstract fun dialPlanRuleDao(): DialPlanRuleDao
    abstract fun dialPlanScheduleDao(): DialPlanScheduleDao
    abstract fun registrationDao(): RegistrationDao

    companion object {
        /** 5 -> 6: adds the persisted registrations table, keeps all existing data. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `registrations` (" +
                        "`extension` TEXT NOT NULL, `ip` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, `transport` TEXT NOT NULL, " +
                        "`userAgent` TEXT NOT NULL, `lastSeen` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`extension`))"
                )
            }
        }

        @Volatile
        private var INSTANCE: AsteriskDatabase? = null

        fun getInstance(context: Context): AsteriskDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AsteriskDatabase::class.java,
                    "asterisk_database"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}