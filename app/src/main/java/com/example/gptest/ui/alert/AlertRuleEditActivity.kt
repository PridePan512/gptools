package com.example.gptest.ui.alert

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gptest.R
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.WatchlistStore
import com.example.gptest.databinding.ActivityAlertRuleEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertRuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertRuleEditBinding
    private lateinit var conditionAdapter: AlertConditionAdapter
    private var ruleId: String = ""
    private var isNewRule = true
    private var enabled = true
    private var status = AlertRuleStatus.WAITING
    private var lastTriggered: String? = null
    private val conditions = mutableListOf<AlertCondition>()
    private val watchlistStocks = ArrayList<AlertStockOption>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        watchlistStocks.addAll(AlertWatchlistExtras.get(intent))

        val incomingId = intent.getStringExtra(EXTRA_RULE_ID)
        val existing = incomingId?.let { AlertPreviewStore.get(it) }
        if (existing != null) {
            isNewRule = false
            ruleId = existing.id
            enabled = existing.enabled
            status = existing.status
            lastTriggered = existing.lastTriggered
            conditions.addAll(existing.conditions)
            binding.etRuleName.setText(existing.name)
            binding.toggleMatch.check(
                if (existing.matchMode == AlertMatchMode.ALL) R.id.btnMatchAll else R.id.btnMatchAny
            )
            binding.rgNotify.check(notifyRadioId(existing.notifyMode))
            binding.btnDelete.visibility = View.VISIBLE
            title = getString(R.string.alert_rule_edit)
        } else {
            ruleId = AlertPreviewStore.newId()
            binding.toggleMatch.check(R.id.btnMatchAll)
            binding.rgNotify.check(R.id.rbNotifyCooldown)
            title = getString(R.string.alert_rule_create)
        }

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
        renderConditions()
        refreshWatchlistFromDb()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
                WatchlistStore(AppDatabase.get(this@AlertRuleEditActivity).watchlistDao()).load()
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
        val name = binding.etRuleName.text?.toString().orEmpty().trim().ifBlank {
            conditions.firstOrNull()?.sentence().orEmpty().ifBlank { getString(R.string.alert_rule_create) }
        }
        AlertPreviewStore.upsert(
            AlertRule(
                id = ruleId,
                name = name,
                enabled = enabled,
                matchMode = currentMatchMode(),
                notifyMode = currentNotifyMode(),
                conditions = conditions.toList(),
                status = if (isNewRule) AlertRuleStatus.WAITING else status,
                lastTriggered = lastTriggered
            )
        )
        Toast.makeText(this, R.string.alert_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.alert_delete_rule)
            .setMessage(binding.etRuleName.text?.toString().orEmpty().ifBlank { getString(R.string.alert_rule_edit) })
            .setPositiveButton(R.string.delete) { _, _ ->
                AlertPreviewStore.delete(ruleId)
                Toast.makeText(this, R.string.alert_deleted, Toast.LENGTH_SHORT).show()
                finish()
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
