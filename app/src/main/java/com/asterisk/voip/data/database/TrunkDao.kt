package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.Trunk
import kotlinx.coroutines.flow.Flow

@Dao
interface TrunkDao {
    @Query("SELECT * FROM trunks ORDER BY priority ASC, name ASC")
    fun getAll(): Flow<List<Trunk>>

    @Query("SELECT * FROM trunks ORDER BY priority ASC, name ASC")
    suspend fun getAllNow(): List<Trunk>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trunk: Trunk): Long

    @Update
    suspend fun update(trunk: Trunk)

    @Delete
    suspend fun delete(trunk: Trunk)
}
