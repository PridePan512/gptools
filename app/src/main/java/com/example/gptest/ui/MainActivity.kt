package com.example.gptest.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.QuoteRepository
import com.example.gptest.data.SortPreferences
import com.example.gptest.data.WatchlistStore
import com.example.gptest.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var quoteAdapter: QuoteListAdapter

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            QuoteRepository(),
            WatchlistStore(AppDatabase.get(this).watchlistDao()),
            SortPreferences(this)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupQuoteList()
        binding.btnSort.setOnClickListener { showSortMenu() }
        binding.btnAdd.setOnClickListener { addCode() }
        binding.btnAdd.isEnabled = false
        binding.etStockCode.isEnabled = false
        binding.etStockCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCode()
                true
            } else {
                false
            }
        }
        binding.btnStart.setOnClickListener {
            viewModel.startPolling(binding.etInterval.text?.toString().orEmpty())
        }
        binding.etInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.saveInterval(binding.etInterval.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnStop.setOnClickListener { viewModel.stopPolling() }
        binding.quoteCard.tvToggleRaw.setOnClickListener { viewModel.toggleRaw() }
        observeViewModel()
    }

    override fun onStop() {
        viewModel.saveInterval(binding.etInterval.text?.toString().orEmpty())
        super.onStop()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            UiEvent.ClearCodeInput -> binding.etStockCode.text?.clear()
                            is UiEvent.OfferUndoDelete -> showUndoSnackbar(event.label)
                        }
                    }
                }
            }
        }
    }

    private fun render(state: MainUiState) {
        binding.btnAdd.isEnabled = state.watchlistLoaded
        binding.etStockCode.isEnabled = state.watchlistLoaded
        binding.etInterval.isEnabled = !state.isRunning
        val intervalText = state.intervalSeconds.toString()
        if (!binding.etInterval.hasFocus() && binding.etInterval.text?.toString() != intervalText) {
            binding.etInterval.setText(intervalText)
        }
        binding.btnStart.isEnabled = !state.isRunning
        binding.btnStop.isEnabled = state.isRunning
        binding.root.keepScreenOn = state.isRunning
        binding.btnSort.setText(sortLabel(state.sortMode))
        binding.tvStatus.text = statusText(state.status)
        quoteAdapter.submit(state.rows, state.selectedCode, state.dragEnabled)
        bindSelectedCard(state)
    }

    private fun addCode() {
        viewModel.addCode(binding.etStockCode.text?.toString().orEmpty())
    }

    private fun showUndoSnackbar(label: String) {
        Snackbar.make(
            binding.root,
            getString(R.string.snackbar_deleted, label),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            viewModel.undoRemove()
        }.show()
    }

    private fun setupQuoteList() {
        quoteAdapter = QuoteListAdapter(
            onClick = { quote -> viewModel.select(quote.requestCode) },
            onDelete = { code -> viewModel.removeCode(code) },
            onStartDrag = { holder ->
                binding.rvQuoteList.parent?.requestDisallowInterceptTouchEvent(true)
                itemTouchHelper.startDrag(holder)
            },
            applyChangeColor = { view, change -> applyChangeColor(view, change) }
        )
        binding.rvQuoteList.layoutManager = LinearLayoutManager(this)
        binding.rvQuoteList.adapter = quoteAdapter
        binding.rvQuoteList.isNestedScrollingEnabled = false
        itemTouchHelper.attachToRecyclerView(binding.rvQuoteList)
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val dragFlags = if (quoteAdapter.dragEnabled) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else {
                0
            }
            return makeMovementFlags(dragFlags, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false
            }
            Collections.swap(quoteAdapter.items, from, to)
            quoteAdapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (quoteAdapter.dragEnabled) {
                viewModel.reorder(quoteAdapter.items.map { it.quote.requestCode })
            }
        }
    })

    private fun showSortMenu() {
        PopupMenu(this, binding.btnSort).apply {
            menu.add(0, QuoteSortMode.CHANGE_PERCENT.ordinal, 0, R.string.sort_change)
            menu.add(0, QuoteSortMode.AMOUNT.ordinal, 1, R.string.sort_amount)
            menu.add(0, QuoteSortMode.PRICE.ordinal, 2, R.string.sort_price)
            menu.add(0, QuoteSortMode.CUSTOM.ordinal, 3, R.string.sort_custom)
            setOnMenuItemClickListener { item ->
                val selected = QuoteSortMode.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                viewModel.setSortMode(selected)
                true
            }
            show()
        }
    }

    private fun sortLabel(mode: QuoteSortMode): Int = when (mode) {
        QuoteSortMode.CUSTOM -> R.string.sort_custom
        QuoteSortMode.CHANGE_PERCENT -> R.string.sort_change
        QuoteSortMode.AMOUNT -> R.string.sort_amount
        QuoteSortMode.PRICE -> R.string.sort_price
    }

    private fun statusText(status: QuoteStatus): String = when (status) {
        QuoteStatus.Idle -> getString(R.string.status_idle)
        QuoteStatus.Running -> getString(R.string.status_running)
        QuoteStatus.Stopped -> getString(R.string.status_stopped)
        QuoteStatus.InvalidCode -> getString(R.string.status_invalid_code)
        QuoteStatus.DuplicateCode -> getString(R.string.status_duplicate_code)
        QuoteStatus.EmptyWatchlist -> getString(R.string.status_empty_watchlist)
        QuoteStatus.InvalidResponse -> getString(R.string.status_invalid_response)
        is QuoteStatus.NetworkError -> getString(R.string.status_error, status.message)
        is QuoteStatus.SessionOnce -> when (status.phase) {
            TradingSession.Phase.PRE_OPEN -> getString(R.string.status_preopen_once)
            TradingSession.Phase.LUNCH -> getString(R.string.status_lunch_once)
            TradingSession.Phase.CLOSED -> getString(R.string.status_closed_once)
            TradingSession.Phase.OPEN -> getString(R.string.status_stopped)
        }
    }

    private fun bindSelectedCard(state: MainUiState) {
        val card = binding.quoteCard
        val data = state.selectedCard
        if (data == null) {
            card.tvCardPlaceholder.visibility = View.VISIBLE
            card.llCardContent.visibility = View.GONE
            return
        }
        card.tvCardPlaceholder.visibility = View.GONE
        card.llCardContent.visibility = View.VISIBLE
        card.tvCardName.text = data.name
        card.tvCardCode.text = data.code
        card.tvCardPrice.text = data.price
        card.tvCardChangePercent.text = data.changePercent
        card.tvCardChange.text = data.change
        applyChangeColor(card.tvCardChangePercent, data.changePercent)
        applyChangeColor(card.tvCardChange, data.changePercent)
        card.tvOpen.text = data.open
        card.tvPrevClose.text = data.prevClose
        card.tvHigh.text = data.high
        card.tvLow.text = data.low
        card.tvVolume.text = data.volume
        card.tvAmount.text = data.amount
        card.tvTurnover.text = data.turnover
        card.tvAmplitude.text = data.amplitude
        card.tvPe.text = data.pe
        card.tvPb.text = data.pb
        card.tvBids.text = data.bids.mapIndexed { index, level ->
            getString(R.string.level_bid, index + 1, level.price, level.volume)
        }.joinToString("\n")
        card.tvAsks.text = data.asks.mapIndexed { index, level ->
            getString(R.string.level_ask, index + 1, level.price, level.volume)
        }.joinToString("\n")
        card.tvRawDetail.text = data.rawDetail
        card.tvRawDetail.visibility = if (state.rawExpanded) View.VISIBLE else View.GONE
        card.tvToggleRaw.setText(
            if (state.rawExpanded) R.string.hide_raw_fields else R.string.show_raw_fields
        )
    }

    private fun applyChangeColor(view: TextView, changePercent: String) {
        val changeValue = QuoteParser.changePercentValue(changePercent)
        val changeColor = when {
            changeValue == null || changeValue == 0.0 -> R.color.quote_flat
            changeValue > 0 -> R.color.quote_up
            else -> R.color.quote_down
        }
        view.setTextColor(getColor(changeColor))
    }
}
