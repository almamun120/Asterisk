package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.Extension
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY extensionNumber ASC")
    fun getAllExtensions(): Flow<List<Extension>>

    @Query("SELECT * FROM extensions WHERE enabled = 1")
    fun getEnabledExtensions(): Flow<List<Extension>>

    @Query("SELECT * FROM extensions WHERE id = :id")
    suspend fun getExtensionById(id: Long): Extension?

    @Query("SELECT * FROM extensions WHERE extensionNumber = :number")
    suspend fun getExtensionByNumber(number: String): Extension?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtension(extension: Extension): Long

    @Update
    suspend fun updateExtension(extension: Extension)

    @Delete
    suspend fun deleteExtension(extension: Extension)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun deleteExtensionById(id: Long)

    @Query("SELECT COUNT(*) FROM extensions")
    suspend fun getExtensionCount(): Int
}