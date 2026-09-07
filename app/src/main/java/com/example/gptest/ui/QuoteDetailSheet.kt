package com.example.gptest.ui

import android.content.Context
import com.example.gptest.R
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.databinding.DialogQuoteDetailBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class QuoteDetailSheet(
    private val context: Context,
    private val onDismiss: () -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = DialogQuoteDetailBinding.inflate(dialog.layoutInflater)
    private var requestCode: String? = null
    private var rawExpanded = false

    init {
        dialog.setContentView(binding.root)
        dialog.setOnDismissListener { onDismiss() }
        binding.quoteCard.tvToggleRaw.setOnClickListener {
            rawExpanded = !rawExpanded
            bind(QuoteMonitorHolder.get(context).uiState.value)
        }
    }

    fun show(code: String) {
        requestCode = code
        rawExpanded = false
        bind(QuoteMonitorHolder.get(context).uiState.value)
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
    }
}
