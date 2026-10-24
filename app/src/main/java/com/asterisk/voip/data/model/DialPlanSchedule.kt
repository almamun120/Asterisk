// FILE: ./app/src/main/java/com/asterisk/voip/data/model/DialPlanSchedule.kt
package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dialplan_schedules")
data class DialPlanSchedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val daysOfWeek: String = "1,2,3,4,5", // 1=Monday, 7=Sunday
    val startTime: String = "09:00", // HH:mm format
    val endTime: String = "17:00",
    val timezone: String = "UTC",
    val enabled: Boolean = true
)