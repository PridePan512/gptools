package com.example.gptest.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val matchMode: String,
    val notifyMode: String,
    val status: String,
    val lastTriggeredMs: Long?,
    val onceConsumed: Boolean,
    val sortOrder: Int
)

@Entity(
    tableName = "alert_conditions",
    foreignKeys = [
        ForeignKey(
            entity = AlertRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ruleId")]
)
data class AlertConditionEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val stockCode: String,
    val stockName: String,
    val metric: String,
    val operator: String,
    val numberValue: String,
    val compareCode: String?,
    val compareName: String?,
    val sortOrder: Int
)
