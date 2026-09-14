package com.example.gptest.ui

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.R
import com.example.gptest.data.HistoryRepository
import com.example.gptest.databinding.DialogQuoteDetailBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    }

    fun show(code: String) {
        requestCode = code
        rawExpanded = false
        bind(QuoteMonitorHolder.get(context).uiState.value)
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

}
