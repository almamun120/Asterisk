package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.Voicemail

@Dao
interface VoicemailDao {
    @Insert
    suspend fun insert(v: Voicemail): Long

    @Query("SELECT * FROM voicemails WHERE mailbox = :mailbox ORDER BY timestamp DESC")
    suspend fun forMailbox(mailbox: String): List<Voicemail>

    @Query("SELECT COUNT(*) FROM voicemails WHERE mailbox = :mailbox AND heard = 0")
    suspend fun unheardCount(mailbox: String): Int

    @Query("SELECT COUNT(*) FROM voicemails WHERE mailbox = :mailbox")
    suspend fun totalCount(mailbox: String): Int

    @Query("UPDATE voicemails SET heard = 1 WHERE id = :id")
    suspend fun markHeard(id: Long)

    @Query("DELETE FROM voicemails WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM voicemails WHERE timestamp < :before")
    suspend fun olderThan(before: Long): List<Voicemail>

    @Query("DELETE FROM voicemails WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
