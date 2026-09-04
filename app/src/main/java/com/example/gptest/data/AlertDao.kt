package com.example.gptest.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_rules ORDER BY sortOrder ASC")
    fun getRules(): List<AlertRuleEntity>

    @Query("SELECT * FROM alert_conditions ORDER BY sortOrder ASC")
    fun getConditions(): List<AlertConditionEntity>

    @Query("SELECT * FROM alert_rules WHERE id = :id")
    fun getRule(id: String): AlertRuleEntity?

    @Query("SELECT * FROM alert_conditions WHERE ruleId = :ruleId ORDER BY sortOrder ASC")
    fun getConditions(ruleId: String): List<AlertConditionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRule(rule: AlertRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertConditions(conditions: List<AlertConditionEntity>)

    @Query("DELETE FROM alert_conditions WHERE ruleId = :ruleId")
    fun deleteConditions(ruleId: String)

    @Query("DELETE FROM alert_rules WHERE id = :id")
    fun deleteRule(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM alert_rules")
    fun maxSortOrder(): Int

    @Transaction
    fun replaceRule(rule: AlertRuleEntity, conditions: List<AlertConditionEntity>) {
        upsertRule(rule)
        deleteConditions(rule.id)
        if (conditions.isNotEmpty()) {
            upsertConditions(conditions)
        }
    }
}
