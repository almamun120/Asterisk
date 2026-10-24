// FILE: ./app/src/main/java/com/asterisk/voip/data/database/DialPlanScheduleDao.kt
package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.DialPlanSchedule
import kotlinx.coroutines.flow.Flow

@Dao
interface DialPlanScheduleDao {
    @Query("SELECT * FROM dialplan_schedules ORDER BY name ASC")
    fun getAllSchedules(): Flow<List<DialPlanSchedule>>

    @Query("SELECT * FROM dialplan_schedules WHERE enabled = 1")
    fun getEnabledSchedules(): Flow<List<DialPlanSchedule>>

    @Query("SELECT * FROM dialplan_schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): DialPlanSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: DialPlanSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: DialPlanSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: DialPlanSchedule)
}