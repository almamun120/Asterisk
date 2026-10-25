// FILE: ./app/src/main/java/com/asterisk/voip/data/database/DialPlanRuleDao.kt
package com.asterisk.voip.data.database

import androidx.room.*
import com.asterisk.voip.data.model.DialPlanRule
import com.asterisk.voip.data.model.DialPlanCondition
import kotlinx.coroutines.flow.Flow

@Dao
interface DialPlanRuleDao {
    @Query("SELECT * FROM dialplan_rules ORDER BY priority ASC, name ASC")
    fun getAllRules(): Flow<List<DialPlanRule>>

    @Query("SELECT * FROM dialplan_rules WHERE enabled = 1 ORDER BY priority ASC")
    fun getEnabledRules(): Flow<List<DialPlanRule>>

    @Query("SELECT * FROM dialplan_rules WHERE enabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledRulesNow(): List<DialPlanRule>

    @Query("SELECT * FROM dialplan_rules WHERE `condition` = :condition AND enabled = 1 ORDER BY priority ASC")
    suspend fun getRulesByCondition(condition: DialPlanCondition): List<DialPlanRule>

    @Query("SELECT * FROM dialplan_rules WHERE pattern = :pattern AND enabled = 1 ORDER BY priority ASC")
    suspend fun getRulesByPattern(pattern: String): List<DialPlanRule>

    @Query("SELECT * FROM dialplan_rules WHERE id = :id")
    suspend fun getRuleById(id: Long): DialPlanRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: DialPlanRule): Long

    @Update
    suspend fun updateRule(rule: DialPlanRule)

    @Delete
    suspend fun deleteRule(rule: DialPlanRule)

    @Query("DELETE FROM dialplan_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)
}