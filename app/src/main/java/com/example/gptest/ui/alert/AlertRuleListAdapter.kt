package com.example.gptest.ui.alert

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.databinding.ItemAlertRuleBinding

class AlertRuleListAdapter(
    private val onClick: (AlertRule) -> Unit,
    private val onEnabledChange: (AlertRule, Boolean) -> Unit
) : RecyclerView.Adapter<AlertRuleListAdapter.ViewHolder>() {

    private val items = mutableListOf<AlertRule>()

    fun submit(rules: List<AlertRule>) {
        items.clear()
        items.addAll(rules)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlertRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemAlertRuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: AlertRule) {
            val context = binding.root.context
            binding.tvRuleName.text = rule.displayName()
            binding.tvRuleSummary.text = rule.summary()
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = rule.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                onEnabledChange(rule, checked)
            }
            val (statusText, statusBg, statusColor) = when (rule.status) {
                AlertRuleStatus.WAITING -> Triple(
                    R.string.alert_status_waiting,
                    R.drawable.bg_alert_chip_waiting,
                    R.color.teal_700
                )
                AlertRuleStatus.FIRED -> Triple(
                    R.string.alert_status_fired,
                    R.drawable.bg_alert_chip_fired,
                    R.color.quote_up
                )
                AlertRuleStatus.OFF -> Triple(
                    R.string.alert_status_off,
                    R.drawable.bg_alert_chip_off,
                    R.color.quote_flat
                )
            }
            binding.tvRuleStatus.setText(statusText)
            binding.tvRuleStatus.setBackgroundResource(statusBg)
            binding.tvRuleStatus.setTextColor(ContextCompat.getColor(context, statusColor))
            val triggered = AlertEvaluator.formatTriggered(rule.lastTriggeredMs)
            binding.tvLastTriggered.text = if (triggered.isNullOrBlank()) {
                context.getString(R.string.alert_never_triggered)
            } else {
                context.getString(R.string.alert_last_triggered, triggered)
            }
            binding.root.setOnClickListener { onClick(rule) }
        }
    }
}
