package com.example.gptest.ui.alert

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import com.example.gptest.R
import com.example.gptest.databinding.DialogAddConditionBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout

class AddConditionSheet(
    context: Context,
    private val stocks: List<AlertStockOption>,
    private val existing: AlertCondition?,
    private val onConfirm: (AlertCondition) -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = DialogAddConditionBinding.inflate(dialog.layoutInflater)

    fun show() {
        dialog.setContentView(binding.root)
        binding.tvSheetTitle.setText(
            if (existing == null) R.string.alert_add_condition else R.string.alert_condition_edit
        )
        binding.btnConfirmCondition.setText(
            if (existing == null) R.string.alert_condition_confirm else R.string.alert_condition_edit
        )
        val stockLabels = stocks.map { it.menuLabel }
        val metricLabels = AlertMetric.entries.map { it.label }
        val operatorLabels = AlertOperator.entries.map { it.label }
        bindDropdown(binding.tilStock, binding.actStock, stockLabels)
        bindDropdown(binding.tilMetric, binding.actMetric, metricLabels)
        bindDropdown(binding.tilOperator, binding.actOperator, operatorLabels)
        bindDropdown(binding.tilCompareStock, binding.actCompareStock, stockLabels)

        val seed = existing
        if (seed != null) {
            binding.actStock.setText(seed.stock.menuLabel, false)
            binding.actMetric.setText(seed.metric.label, false)
            binding.actOperator.setText(seed.operator.label, false)
            val compare = seed.compareStock != null
            binding.switchCompare.isChecked = compare
            binding.etValue.setText(seed.numberValue)
            binding.actCompareStock.setText(seed.compareStock?.menuLabel.orEmpty(), false)
        } else {
            binding.actMetric.setText(AlertMetric.PRICE.label, false)
            binding.actOperator.setText(AlertOperator.CROSS_UP.label, false)
        }
        refreshValueVisibility()
        refreshPreview()

        binding.switchCompare.setOnCheckedChangeListener { _, _ ->
            refreshValueVisibility()
            refreshPreview()
        }
        listOf(binding.actStock, binding.actMetric, binding.actOperator, binding.actCompareStock).forEach { view ->
            view.setOnItemClickListener { _, _, _, _ ->
                refreshValueVisibility()
                refreshPreview()
            }
        }
        binding.etValue.setOnFocusChangeListener { _, _ -> refreshPreview() }
        binding.etValue.addTextChangedListener(SimpleTextWatcher { refreshPreview() })
        binding.btnConfirmCondition.setOnClickListener { confirm() }
        dialog.show()
    }

    private fun confirm() {
        val stock = selectedStock(binding.actStock.text.toString())
        if (stock == null) {
            Toast.makeText(binding.root.context, R.string.alert_condition_need_stock, Toast.LENGTH_SHORT).show()
            return
        }
        val operator = AlertOperator.entries.find { it.label == binding.actOperator.text.toString() }
            ?: AlertOperator.CROSS_UP
        val metric = if (operator.needsValue) {
            AlertMetric.entries.find { it.label == binding.actMetric.text.toString() } ?: AlertMetric.PRICE
        } else {
            AlertMetric.PRICE
        }
        val compare = if (operator.needsValue && binding.switchCompare.isChecked) {
            selectedStock(binding.actCompareStock.text.toString())
        } else {
            null
        }
        if (operator.needsValue && binding.switchCompare.isChecked && compare == null) {
            Toast.makeText(binding.root.context, R.string.alert_condition_need_stock, Toast.LENGTH_SHORT).show()
            return
        }
        val number = if (operator.needsValue) {
            binding.etValue.text?.toString().orEmpty().trim()
        } else {
            ""
        }
        if (operator.needsValue && !binding.switchCompare.isChecked && number.isEmpty()) {
            Toast.makeText(binding.root.context, R.string.alert_condition_need_value, Toast.LENGTH_SHORT).show()
            return
        }
        onConfirm(
            AlertCondition(
                id = existing?.id ?: AlertIds.newId(),
                stock = stock,
                metric = metric,
                operator = operator,
                numberValue = number,
                compareStock = compare
            )
        )
        dialog.dismiss()
    }

    private fun refreshValueVisibility() {
        val operator = AlertOperator.entries.find { it.label == binding.actOperator.text.toString() }
            ?: AlertOperator.CROSS_UP
        if (!operator.needsValue) {
            binding.tilMetric.visibility = View.GONE
            binding.switchCompare.visibility = View.GONE
            binding.tilValue.visibility = View.GONE
            binding.tilCompareStock.visibility = View.GONE
            return
        }
        binding.tilMetric.visibility = View.VISIBLE
        binding.switchCompare.visibility = View.VISIBLE
        val compare = binding.switchCompare.isChecked
        binding.tilValue.visibility = if (compare) View.GONE else View.VISIBLE
        binding.tilCompareStock.visibility = if (compare) View.VISIBLE else View.GONE
    }

    private fun refreshPreview() {
        val stock = selectedStock(binding.actStock.text.toString())
        val metric = AlertMetric.entries.find { it.label == binding.actMetric.text.toString() }
            ?: AlertMetric.PRICE
        val operator = AlertOperator.entries.find { it.label == binding.actOperator.text.toString() }
            ?: AlertOperator.CROSS_UP
        val compare = if (binding.switchCompare.isChecked) {
            selectedStock(binding.actCompareStock.text.toString())
        } else {
            null
        }
        val draft = AlertCondition(
            id = "preview",
            stock = stock ?: AlertStockOption("", "未选择股票"),
            metric = metric,
            operator = operator,
            numberValue = binding.etValue.text?.toString().orEmpty().ifBlank { "—" },
            compareStock = compare
        )
        binding.tvPreviewSentence.text = draft.sentence()
    }

    private fun selectedStock(label: String): AlertStockOption? {
        return stocks.find { it.menuLabel == label || it.name == label || it.code == label }
    }

    private fun bindDropdown(
        layout: TextInputLayout,
        view: AutoCompleteTextView,
        items: List<String>
    ) {
        view.setAdapter(ArrayAdapter(view.context, android.R.layout.simple_list_item_1, items))
        view.threshold = 1
        var skipShowAfterDismiss = false
        view.setOnDismissListener {
            skipShowAfterDismiss = true
            view.postDelayed({ skipShowAfterDismiss = false }, 150)
        }
        val togglePopup = View.OnClickListener {
            if (skipShowAfterDismiss || view.isPopupShowing) {
                view.dismissDropDown()
            } else {
                view.showDropDown()
            }
        }
        view.setOnClickListener(togglePopup)
        view.setOnTouchListener { v, event ->
            if (event.actionMasked != MotionEvent.ACTION_UP) {
                return@setOnTouchListener false
            }
            v.performClick()
            true
        }
        layout.setEndIconOnClickListener(togglePopup)
        view.onFocusChangeListener = null
    }
}

private class SimpleTextWatcher(
    private val onChanged: () -> Unit
) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) = onChanged()
}
