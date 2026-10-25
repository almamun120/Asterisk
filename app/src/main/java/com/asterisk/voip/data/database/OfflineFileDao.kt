package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.OfflineFile

@Dao
interface OfflineFileDao {
    @Insert
    suspend fun insert(f: OfflineFile): Long

    @Query("SELECT * FROM offline_files WHERE toExt = :ext AND delivered = 0 ORDER BY timestamp ASC")
    suspend fun pendingFor(ext: String): List<OfflineFile>

    @Query("SELECT COUNT(*) FROM offline_files WHERE toExt = :ext AND delivered = 0")
    suspend fun pendingCount(ext: String): Int

    @Query("UPDATE offline_files SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)

    @Query("SELECT * FROM offline_files WHERE expiresAt < :now OR (delivered = 1 AND timestamp < :deliveredBefore)")
    suspend fun stale(now: Long, deliveredBefore: Long): List<OfflineFile>

    @Query("DELETE FROM offline_files WHERE id = :id")
    suspend fun delete(id: Long)
}
