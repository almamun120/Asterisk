package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.Registration

@Dao
interface RegistrationDao {
    @Query("SELECT * FROM registrations")
    suspend fun getAll(): List<Registration>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(registration: Registration)

    @Query("DELETE FROM registrations WHERE extension = :extension")
    suspend fun delete(extension: String)

    @Query("DELETE FROM registrations WHERE lastSeen < :olderThan")
    suspend fun deleteStale(olderThan: Long)
}
