package com.example.gptest.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gptest.R
import com.example.gptest.databinding.DialogQuickAlertsBinding
import com.example.gptest.ui.alert.AlertNotificationPermission
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertQuickAdd
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickAlertDialog(
    private val activity: FragmentActivity,
    private val code: String,
    name: String,
    onDismiss: () -> Unit
) {
    private val builder = MaterialAlertDialogBuilder(activity)
    private val binding = DialogQuickAlertsBinding.inflate(android.view.LayoutInflater.from(builder.context))
    private val dialog = builder
        .setTitle(activity.getString(R.string.quick_alert_dialog_title, name, code))
        .setView(binding.root)
        .setPositiveButton(android.R.string.ok, null)
        .create()
    private var loadJob: Job? = null
    private var selected: Set<AlertOperator> = emptySet()
    private var ready = false
    private val buttons = mutableMapOf<AlertOperator, MaterialButton>()

    init {
        val available = AlertQuickAdd.availableOperators(code)
        addGroup(R.string.quick_group_ma, available.filter { it.maPeriod != null })
        addGroup(R.string.quick_group_board, available.filter { it in setOf(
            AlertOperator.LIMIT_UP, AlertOperator.LIMIT_DOWN, AlertOperator.OPEN_LIMIT_UP, AlertOperator.OPEN_LIMIT_DOWN
        ) })
        addGroup(R.string.quick_group_rapid, available.filter { it in setOf(
            AlertOperator.PRICE_SURGE, AlertOperator.PRICE_DROP, AlertOperator.VOLUME_SURGE, AlertOperator.VOLUME_SHRINK
        ) })
        dialog.setOnDismissListener {
            loadJob?.cancel()
            onDismiss()
        }
    }

    private fun addGroup(title: Int, operators: List<AlertOperator>) {
        if (operators.isEmpty()) return
        binding.alertGroups.addView(TextView(builder.context).apply {
            setText(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(0, dp(20), 0, dp(6))
        })
        operators.chunked(2).forEach { pair ->
            val row = LinearLayout(builder.context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, operator ->
                val button = MaterialButton(builder.context).apply {
                    isCheckable = true
                    isEnabled = false
                    isAllCaps = false
                    textSize = 14f
                    minWidth = 0
                    minimumWidth = 0
                    minimumHeight = dp(68)
                    cornerRadius = dp(12)
                    strokeWidth = dp(1)
                    insetTop = dp(4)
                    insetBottom = dp(4)
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    text = operator.maPeriod?.let { "${operator.label}\nMA$it" } ?: operator.label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index == 0) marginEnd = dp(8)
                    }
                    setOnClickListener {
                        if (!ready) return@setOnClickListener
                        if (operator in selected) selected = selected - operator else {
                            AlertNotificationPermission.requestIfNeeded(activity)
                            selected = selected + operator
                        }
                        bindChecks()
                        QuoteMonitorHolder.get(activity).addQuickAlert(code, operator)
                    }
                }
                buttons[operator] = button
                row.addView(button)
            }
            binding.alertGroups.addView(row)
        }
        bindChecks()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show() {
        dialog.show()
        refresh()
    }

    // Reload this stock's rules: completion events can belong to another stock.
    fun refresh() {
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            selected = withContext(Dispatchers.IO) {
                QuoteMonitorHolder.get(activity).quickAlertOperators(code)
            }
            ready = true
            bindChecks()
        }
    }

    private fun bindChecks() {
        binding.tvSelectionSummary.text = if (ready) activity.getString(R.string.quick_alert_selected, selected.size)
            else activity.getString(R.string.quick_alert_loading)
        for ((operator, button) in buttons) {
            val checked = operator in selected
            button.isChecked = checked
            button.isEnabled = ready
            val background = if (checked) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurface
            val foreground = if (checked) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurface
            val outline = if (checked) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant
            button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, background))
            button.setTextColor(MaterialColors.getColor(button, foreground))
            button.strokeColor = ColorStateList.valueOf(MaterialColors.getColor(button, outline))
        }
    }
}
