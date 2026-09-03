package com.example.gptest.ui.alert

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.databinding.ItemAlertConditionBinding

class AlertConditionAdapter(
    private val joiner: () -> String,
    private val onEdit: (AlertCondition) -> Unit,
    private val onDelete: (AlertCondition) -> Unit
) : RecyclerView.Adapter<AlertConditionAdapter.ViewHolder>() {

    private val items = mutableListOf<AlertCondition>()

    fun submit(conditions: List<AlertCondition>) {
        items.clear()
        items.addAll(conditions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlertConditionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemAlertConditionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(condition: AlertCondition, position: Int) {
            val context = binding.root.context
            binding.tvJoiner.visibility = if (position == 0) View.GONE else View.VISIBLE
            binding.tvJoiner.text = joiner()
            binding.tvConditionIndex.text = context.getString(R.string.alert_condition_index, position + 1)
            binding.tvConditionSentence.text = condition.sentence()
            binding.tvConditionHint.text = context.getString(R.string.alert_condition_tap_edit)
            binding.root.setOnClickListener { onEdit(condition) }
            binding.btnDeleteCondition.setOnClickListener { onDelete(condition) }
        }
    }
}
