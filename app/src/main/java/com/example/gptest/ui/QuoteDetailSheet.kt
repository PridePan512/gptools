package com.example.gptest.ui

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.databinding.DialogQuoteDetailBinding
import com.example.gptest.ui.alert.AlertNotificationPermission
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertQuickAdd
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
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

    init {
        dialog.setContentView(binding.root)
        dialog.setOnDismissListener { onDismiss() }
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
        bindChipChecks()
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
