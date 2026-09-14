package com.example.gptest.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.gptest.business.MovingAverageCalculator
import com.google.android.material.card.MaterialCardView
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
        binding.quoteCard.btnRetryHistory.setOnClickListener { requestCode?.let { loadHistory(it) } }
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
        binding.quoteCard.tvMovingAverages.text = context.getString(R.string.history_loading)
        binding.quoteCard.tvMovingAverages.visibility = View.VISIBLE
        binding.quoteCard.maGrid.removeAllViews()
        binding.quoteCard.tvHistoryInfo.text = ""
        binding.quoteCard.btnRetryHistory.visibility = View.GONE
        val owner = context as? LifecycleOwner ?: return
        historyJob = owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { historyRepository.fetchDaily(code) }
            if (requestCode != code) return@launch
            result.onSuccess { history ->
                val averages = MovingAverageCalculator.calculate(history.closes)
                val snapshotPrice = history.closes.maxBy { it.date }.close
                binding.quoteCard.tvMovingAverages.visibility = View.GONE
                val labels = listOf(R.string.ma5_label, R.string.ma10_label, R.string.ma20_label,
                    R.string.ma60_label, R.string.ma120_label, R.string.ma250_label)
                averages.values.entries.toList().chunked(2).forEachIndexed { rowIndex, entries ->
                    val row = LinearLayout(context)
                    entries.forEachIndexed { column, entry ->
                        val tile = MaterialCardView(context).apply {
                            radius = dp(12).toFloat()
                            cardElevation = 0f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                setMargins(0, dp(4), if (column == 0) dp(8) else 0, dp(4))
                            }
                        }
                        tile.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            addView(TextView(context).apply {
                                setText(labels[rowIndex * 2 + column])
                                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                            })
                            addView(TextView(context).apply {
                                text = entry.value?.let { String.format(Locale.CHINA, "%.2f", it) }
                                    ?: context.getString(R.string.history_insufficient)
                                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                            })
                            addView(TextView(context).apply {
                                val value = entry.value
                                text = if (value == null) context.getString(R.string.ma_no_comparison)
                                    else context.getString(when {
                                        snapshotPrice > value -> R.string.ma_above
                                        snapshotPrice < value -> R.string.ma_below
                                        else -> R.string.ma_equal
                                    })
                                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                            })
                        })
                        row.addView(tile)
                    }
                    binding.quoteCard.maGrid.addView(row)
                }
                val adjustment = context.getString(if (history.forwardAdjusted) R.string.history_qfq else R.string.history_unadjusted)
                binding.quoteCard.tvHistoryInfo.text = context.getString(R.string.history_info, averages.date.toString(), adjustment)
            }.onFailure {
                binding.quoteCard.tvMovingAverages.setText(R.string.history_failed)
                binding.quoteCard.btnRetryHistory.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

}
