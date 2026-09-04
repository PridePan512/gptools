package com.example.gptest.data

import com.example.gptest.ui.alert.AlertRule

interface AlertDataSource {
    fun loadRules(): List<AlertRule>
    fun get(id: String): AlertRule?
    fun saveRule(rule: AlertRule)
    fun deleteRule(id: String)
    fun replaceRules(rules: List<AlertRule>)
    fun setEnabled(id: String, enabled: Boolean)
}

object NoOpAlertDataSource : AlertDataSource {
    override fun loadRules(): List<AlertRule> = emptyList()
    override fun get(id: String): AlertRule? = null
    override fun saveRule(rule: AlertRule) = Unit
    override fun deleteRule(id: String) = Unit
    override fun replaceRules(rules: List<AlertRule>) = Unit
    override fun setEnabled(id: String, enabled: Boolean) = Unit
}
