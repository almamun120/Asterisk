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
        Registration::class,
        Voicemail::class,
        OfflineFile::class,
        Trunk::class
    ],
    version = 8,
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
    abstract fun voicemailDao(): VoicemailDao
    abstract fun offlineFileDao(): OfflineFileDao
    abstract fun trunkDao(): TrunkDao

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

        /** 6 -> 7: voicemails + offline (store-and-forward) files. Keeps all existing data. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voicemails` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mailbox` TEXT NOT NULL, " +
                        "`fromExt` TEXT NOT NULL, `filePath` TEXT NOT NULL, `durationSec` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `heard` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_files` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fromExt` TEXT NOT NULL, " +
                        "`toExt` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `path` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, `delivered` INTEGER NOT NULL)"
                )
            }
        }

        /** 7 -> 8: gateway (SIP trunk) accounts. Keeps all existing data. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trunks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, `port` INTEGER NOT NULL, `username` TEXT NOT NULL, " +
                        "`password` TEXT NOT NULL, `authUser` TEXT NOT NULL, `domain` TEXT NOT NULL, " +
                        "`outboundProxy` TEXT NOT NULL, `register` INTEGER NOT NULL, `expiry` INTEGER NOT NULL, " +
                        "`keepAlive` INTEGER NOT NULL, `callerId` TEXT NOT NULL, `outPrefixes` TEXT NOT NULL, " +
                        "`stripDigits` INTEGER NOT NULL, `addPrefix` TEXT NOT NULL, `priority` INTEGER NOT NULL, " +
                        "`codecs` TEXT NOT NULL, `inboundTarget` TEXT NOT NULL, `inboundMap` TEXT NOT NULL, " +
                        "`maxChannels` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
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
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}