package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.OfflineMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineMessageDao {
    @Query("SELECT * FROM offline_messages WHERE delivered = 0 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<OfflineMessage>

    @Query("SELECT * FROM offline_messages WHERE toExt = :extension AND delivered = 0 ORDER BY timestamp ASC")
    suspend fun getPendingMessagesForExtension(extension: String): List<OfflineMessage>

    @Query("SELECT COUNT(*) FROM offline_messages WHERE toExt = :extension AND delivered = 0")
    suspend fun getPendingMessageCount(extension: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: OfflineMessage): Long

    @Update
    suspend fun updateMessage(message: OfflineMessage)

    @Query("UPDATE offline_messages SET delivered = 1 WHERE id = :messageId")
    suspend fun markAsDelivered(messageId: Long)

    @Query("DELETE FROM offline_messages WHERE delivered = 1 AND timestamp < :timestamp")
    suspend fun deleteDeliveredOlderThan(timestamp: Long)

    @Query("DELETE FROM offline_messages WHERE expiresAt < :timestamp")
    suspend fun deleteExpiredMessages(timestamp: Long)

    @Query("DELETE FROM offline_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)
}