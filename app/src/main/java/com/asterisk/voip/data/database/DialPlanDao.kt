package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.DialPlan
import kotlinx.coroutines.flow.Flow

@Dao
interface DialPlanDao {
    @Query("SELECT * FROM dialplans ORDER BY context, priority ASC")
    fun getAllDialPlans(): Flow<List<DialPlan>>

    @Query("SELECT * FROM dialplans WHERE context = :context ORDER BY priority ASC")
    fun getDialPlansByContext(context: String): Flow<List<DialPlan>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDialPlan(dialPlan: DialPlan): Long

    @Update
    suspend fun updateDialPlan(dialPlan: DialPlan)

    @Delete
    suspend fun deleteDialPlan(dialPlan: DialPlan)
}