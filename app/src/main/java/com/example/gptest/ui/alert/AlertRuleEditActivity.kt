package com.example.gptest.ui.alert

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gptest.R
import com.example.gptest.data.AlertStore
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.WatchlistStore
import com.example.gptest.databinding.ActivityAlertRuleEditBinding
import com.example.gptest.ui.applyEdgeToEdgeInsets
import com.example.gptest.ui.prepareEdgeToEdge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertRuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertRuleEditBinding
    private lateinit var conditionAdapter: AlertConditionAdapter
    private val store by lazy { AlertStore(AppDatabase.get(this).alertDao()) }
    private var ruleId: String = ""
    private var isNewRule = true
    private var enabled = true
    private var lastTriggeredMs: Long? = null
    private var readyToSave = false
    private val conditions = mutableListOf<AlertCondition>()
    private val watchlistStocks = ArrayList<AlertStockOption>()

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAlertRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdgeInsets(binding.root, binding.toolbar, binding.content)
        watchlistStocks.addAll(AlertWatchlistExtras.get(intent))

        conditionAdapter = AlertConditionAdapter(
            joiner = { currentMatchMode().joiner },
            onEdit = { condition -> showConditionSheet(condition) },
            onDelete = { condition ->
                conditions.removeAll { it.id == condition.id }
                renderConditions()
            }
        )
        binding.rvConditions.layoutManager = LinearLayoutManager(this)
        binding.rvConditions.adapter = conditionAdapter
        binding.rvConditions.isNestedScrollingEnabled = false
        binding.toggleMatch.addOnButtonCheckedListener { _, _, _ -> conditionAdapter.notifyDataSetChanged() }
        binding.btnAddCondition.setOnClickListener { showConditionSheet(null) }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        val incomingId = intent.getStringExtra(EXTRA_RULE_ID)
        if (incomingId.isNullOrBlank()) {
            bindNewRule()
        } else {
            loadRule(incomingId)
        }
        renderConditions()
        refreshWatchlistFromDb()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindNewRule() {
        isNewRule = true
        ruleId = AlertIds.newId()
        readyToSave = true
        binding.toggleMatch.check(R.id.btnMatchAll)
        binding.rgNotify.check(R.id.rbNotifyCooldown)
        title = getString(R.string.alert_rule_create)
    }

    private fun loadRule(id: String) {
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { store.get(id) }
            if (existing == null) {
                bindNewRule()
            } else {
                isNewRule = false
                ruleId = existing.id
                enabled = existing.enabled
                lastTriggeredMs = existing.lastTriggeredMs
                conditions.clear()
                conditions.addAll(existing.conditions)
                binding.etRuleName.setText(existing.name)
                binding.toggleMatch.check(
                    if (existing.matchMode == AlertMatchMode.ALL) R.id.btnMatchAll else R.id.btnMatchAny
                )
                binding.rgNotify.check(notifyRadioId(existing.notifyMode))
                binding.btnDelete.visibility = View.VISIBLE
                title = getString(R.string.alert_rule_edit)
                renderConditions()
                readyToSave = true
            }
        }
    }

    private fun showConditionSheet(existing: AlertCondition?) {
        val options = watchlistOptions(existing)
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.alert_need_watchlist, Toast.LENGTH_SHORT).show()
            return
        }
        AddConditionSheet(this, options, existing) { updated ->
            val index = conditions.indexOfFirst { it.id == updated.id }
            if (index >= 0) {
                conditions[index] = updated
            } else {
                conditions.add(updated)
            }
            renderConditions()
        }.show()
    }

    private fun watchlistOptions(existing: AlertCondition?): List<AlertStockOption> {
        return (watchlistStocks + listOfNotNull(existing?.stock, existing?.compareStock))
            .distinctBy { it.code }
    }

    private fun refreshWatchlistFromDb() {
        val named = watchlistStocks.toList()
        lifecycleScope.launch {
            val codes = withContext(Dispatchers.IO) {
                WatchlistStore(AppDatabase.get(this@AlertRuleEditActivity).watchlistDao()).allCodes()
            }
            watchlistStocks.clear()
            watchlistStocks.addAll(AlertWatchlistExtras.merge(codes, named))
        }
    }

    private fun renderConditions() {
        conditionAdapter.submit(conditions.toList())
        binding.tvConditionsEmpty.visibility = if (conditions.isEmpty()) View.VISIBLE else View.GONE
        binding.rvConditions.visibility = if (conditions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun save() {
        if (!readyToSave) return
        val name = binding.etRuleName.text?.toString().orEmpty().trim().ifBlank {
            conditions.firstOrNull()?.sentence().orEmpty().ifBlank { getString(R.string.alert_rule_create) }
        }
        val rule = AlertRule(
            id = ruleId,
            name = name,
            enabled = enabled,
            matchMode = currentMatchMode(),
            notifyMode = currentNotifyMode(),
            conditions = conditions.toList(),
            status = if (enabled) AlertRuleStatus.WAITING else AlertRuleStatus.OFF,
            lastTriggeredMs = lastTriggeredMs,
            onceConsumed = false
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.saveRule(rule) }
            Toast.makeText(this@AlertRuleEditActivity, R.string.alert_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alert_delete_rule)
            .setMessage(binding.etRuleName.text?.toString().orEmpty().ifBlank { getString(R.string.alert_rule_edit) })
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.deleteRule(ruleId) }
                    Toast.makeText(this@AlertRuleEditActivity, R.string.alert_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun currentMatchMode(): AlertMatchMode {
        return if (binding.toggleMatch.checkedButtonId == R.id.btnMatchAny) {
            AlertMatchMode.ANY
        } else {
            AlertMatchMode.ALL
        }
    }

    private fun currentNotifyMode(): AlertNotifyMode {
        return when (binding.rgNotify.checkedRadioButtonId) {
            R.id.rbNotifyOnce -> AlertNotifyMode.ONCE
            R.id.rbNotifyAlways -> AlertNotifyMode.ALWAYS
            else -> AlertNotifyMode.COOLDOWN
        }
    }

    private fun notifyRadioId(mode: AlertNotifyMode): Int = when (mode) {
        AlertNotifyMode.ONCE -> R.id.rbNotifyOnce
        AlertNotifyMode.ALWAYS -> R.id.rbNotifyAlways
        AlertNotifyMode.COOLDOWN -> R.id.rbNotifyCooldown
    }

    companion object {
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
