package com.example.gptest.ui

import android.content.Context
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.R
import com.example.gptest.data.HistoryRepository
import com.example.gptest.databinding.DialogQuoteDetailBinding
import com.example.gptest.ui.alert.AlertNotificationPermission
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertQuickAdd
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuoteDetailSheet(
    private val context: Context,
    private val onDismiss: () -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = DialogQuoteDetailBinding.inflate(dialog.layoutInflater)
    private var requestCode: String? = null
    private var rawExpanded = false
    private var existingOperators: Set<AlertOperator> = emptySet()
    private var chipsReady = false
    private var historyJob: Job? = null
    private val historyRepository = HistoryRepository()

    init {
        dialog.setContentView(binding.root)
        dialog.setOnDismissListener {
            historyJob?.cancel()
            onDismiss()
        }
        binding.btnRetryHistory.setOnClickListener { requestCode?.let { loadHistory(it) } }
        binding.quoteCard.tvToggleRaw.setOnClickListener {
            rawExpanded = !rawExpanded
            bind(QuoteMonitorHolder.get(context).uiState.value)
        }
        AlertQuickAdd.operators.forEach { operator ->
            val chip = Chip(context).apply {
                text = operator.label
                isCheckable = true
                isCheckedIconVisible = true
                tag = operator
                setOnClickListener {
                    toggleQuickAlert(operator)
                }
            }
            binding.chipQuickAlerts.addView(chip)
        }
    }

    fun show(code: String) {
        requestCode = code
        rawExpanded = false
        existingOperators = emptySet()
        chipsReady = false
        bindChipEnabled()
        bind(QuoteMonitorHolder.get(context).uiState.value)
        refreshExisting(code)
        loadHistory(code)
        if (!dialog.isShowing) {
            dialog.show()
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun bind(state: MainUiState) {
        val code = requestCode ?: return
        val quote = state.rows.find { it.requestCode == code }
        val card = quote?.let { QuoteCardMapper.from(it) }
        QuoteCardBinder.bind(context, binding.quoteCard, card, rawExpanded)
        bindChipVisibility()
        bindChipChecks()
    }

    private fun loadHistory(code: String) {
        historyJob?.cancel()
        binding.tvMovingAverages.text = context.getString(R.string.history_loading)
        binding.tvHistoryInfo.text = ""
        binding.btnRetryHistory.visibility = View.GONE
        val owner = context as? LifecycleOwner ?: return
        historyJob = owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { historyRepository.fetchAverages(code) }
            if (requestCode != code) return@launch
            result.onSuccess { history ->
                val labels = listOf(R.string.ma5_label, R.string.ma10_label, R.string.ma20_label, R.string.ma250_label)
                binding.tvMovingAverages.text = history.averages.values.entries.mapIndexed { index, entry ->
                    val value = entry.value?.let { String.format(Locale.CHINA, "%.2f", it) }
                        ?: context.getString(R.string.history_insufficient)
                    "${context.getString(labels[index])}：$value"
                }.joinToString("\n")
                val adjustment = context.getString(if (history.forwardAdjusted) R.string.history_qfq else R.string.history_unadjusted)
                binding.tvHistoryInfo.text = context.getString(R.string.history_info, history.averages.date.toString(), adjustment)
            }.onFailure {
                binding.tvMovingAverages.setText(R.string.history_failed)
                binding.btnRetryHistory.visibility = View.VISIBLE
            }
        }
    }

    fun markOperator(operator: AlertOperator) {
        existingOperators = existingOperators + operator
        bindChipChecks()
    }

    fun unmarkOperator(operator: AlertOperator) {
        existingOperators = existingOperators - operator
        bindChipChecks()
    }

    private fun refreshExisting(code: String) {
        val owner = context as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                QuoteMonitorHolder.get(context).quickAlertOperators(code)
            }
            if (requestCode != code) return@launch
            existingOperators = loaded
            chipsReady = true
            bindChipEnabled()
            bindChipChecks()
        }
    }

    private fun bindChipVisibility() {
        val code = requestCode
        val allowed = if (code == null) emptySet() else AlertQuickAdd.availableOperators(code).toSet()
        for (index in 0 until binding.chipQuickAlerts.childCount) {
            val chip = binding.chipQuickAlerts.getChildAt(index) as? Chip ?: continue
            val operator = chip.tag as? AlertOperator ?: continue
            chip.visibility = if (operator in allowed) View.VISIBLE else View.GONE
        }
    }

    private fun bindChipChecks() {
        for (index in 0 until binding.chipQuickAlerts.childCount) {
            val chip = binding.chipQuickAlerts.getChildAt(index) as? Chip ?: continue
            val operator = chip.tag as? AlertOperator ?: continue
            chip.isChecked = operator in existingOperators
        }
    }

    private fun bindChipEnabled() {
        for (index in 0 until binding.chipQuickAlerts.childCount) {
            binding.chipQuickAlerts.getChildAt(index).isEnabled = chipsReady
        }
    }

    private fun toggleQuickAlert(operator: AlertOperator) {
        val code = requestCode ?: return
        if (!chipsReady) return
        if (operator !in AlertQuickAdd.availableOperators(code)) return
        val removing = operator in existingOperators
        if (!removing) {
            (context as? FragmentActivity)?.let { AlertNotificationPermission.requestIfNeeded(it) }
            markOperator(operator)
        } else {
            unmarkOperator(operator)
        }
        QuoteMonitorHolder.get(context).addQuickAlert(code, operator)
    }
}
