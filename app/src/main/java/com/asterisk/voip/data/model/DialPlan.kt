package com.asterisk.voip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dialplans")
data class DialPlan(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val context: String,
    val pattern: String,
    val priority: Int,
    val application: String,
    val arguments: String,
    val enabled: Boolean = true
)