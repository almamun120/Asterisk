// FILE: ./app/src/main/java/com/asterisk/voip/data/database/VoicePromptDao.kt
package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.VoicePrompt
import kotlinx.coroutines.flow.Flow

@Dao
interface VoicePromptDao {
    @Query("SELECT * FROM voice_prompts ORDER BY name ASC")
    fun getAllPrompts(): Flow<List<VoicePrompt>>

    @Query("SELECT * FROM voice_prompts WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledPrompts(): Flow<List<VoicePrompt>>

    @Query("SELECT * FROM voice_prompts WHERE id = :id")
    suspend fun getPromptById(id: Long): VoicePrompt?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrompt(prompt: VoicePrompt): Long

    @Update
    suspend fun updatePrompt(prompt: VoicePrompt)

    @Delete
    suspend fun deletePrompt(prompt: VoicePrompt)

    @Query("DELETE FROM voice_prompts WHERE id = :id")
    suspend fun deletePromptById(id: Long)
}