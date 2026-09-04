package com.example.gptest.ui.alert

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gptest.R
import com.example.gptest.data.AlertStore
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.WatchlistStore
import com.example.gptest.databinding.ActivityAlertRulesBinding
import com.example.gptest.ui.applyEdgeToEdgeInsets
import com.example.gptest.ui.prepareEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertRulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertRulesBinding
    private lateinit var adapter: AlertRuleListAdapter
    private val stocks = ArrayList<AlertStockOption>()
    private val store by lazy { AlertStore(AppDatabase.get(this).alertDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAlertRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyEdgeToEdgeInsets(binding.root, binding.toolbar, binding.content, binding.fabAddRule)
        binding.toolbar.title = getString(R.string.alert_rules_title)
        stocks.addAll(AlertWatchlistExtras.get(intent))
        AlertNotificationPermission.requestIfNeeded(this)

        adapter = AlertRuleListAdapter(
            onClick = { rule -> openEditor(rule.id) },
            onEnabledChange = { rule, enabled ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.setEnabled(rule.id, enabled) }
                    render()
                }
            }
        )
        binding.rvAlertRules.layoutManager = LinearLayoutManager(this)
        binding.rvAlertRules.adapter = adapter
        binding.fabAddRule.setOnClickListener { openEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        render()
        loadWatchlist()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadWatchlist() {
        val named = stocks.toList()
        lifecycleScope.launch {
            val codes = withContext(Dispatchers.IO) {
                WatchlistStore(AppDatabase.get(this@AlertRulesActivity).watchlistDao()).load()
            }
            stocks.clear()
            stocks.addAll(AlertWatchlistExtras.merge(codes, named))
        }
    }

    private fun render() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { store.loadRules() }
            adapter.submit(rules)
            binding.emptyState.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            binding.rvAlertRules.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openEditor(ruleId: String?) {
        startActivity(
            AlertWatchlistExtras.put(
                Intent(this, AlertRuleEditActivity::class.java).putExtra(
                    AlertRuleEditActivity.EXTRA_RULE_ID,
                    ruleId
                ),
                stocks
            )
        )
    }
}
