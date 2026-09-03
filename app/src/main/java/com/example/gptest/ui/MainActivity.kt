package com.example.gptest.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.business.QuoteCardMapper
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.QuoteSorter
import com.example.gptest.business.TradingSession
import com.example.gptest.data.AppDatabase
import com.example.gptest.data.QuoteRepository
import com.example.gptest.data.SortPreferences
import com.example.gptest.data.WatchlistStore
import com.example.gptest.databinding.ActivityMainBinding
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var watchlistStore: WatchlistStore
    private lateinit var sortPreferences: SortPreferences
    private lateinit var quoteAdapter: QuoteListAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val repository = QuoteRepository()
    private val watchlist = mutableListOf<String>()
    private var quotes = emptyList<QuoteSnapshot>()
    private var selectedCode: String? = null
    private var polling = false
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var rawExpanded = false
    private var sortMode = QuoteSortMode.CUSTOM

    private val pollRunnable = Runnable { fetchOnceThenSchedule() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        watchlistStore = WatchlistStore(AppDatabase.get(this).watchlistDao())
        sortPreferences = SortPreferences(this)
        sortMode = sortPreferences.mode
        setupQuoteList()
        binding.btnSort.setOnClickListener { showSortMenu() }
        refreshSortButton()
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
        binding.btnStart.setOnClickListener { startPolling() }
        binding.btnStop.setOnClickListener { stopPolling() }
        binding.quoteCard.tvToggleRaw.setOnClickListener {
            rawExpanded = !rawExpanded
            bindSelectedCard()
        }
        loadWatchlist()
    }

    private fun loadWatchlist() {
        executor.execute {
            val codes = watchlistStore.load()
            handler.post {
                watchlist.clear()
                watchlist.addAll(codes)
                renderQuotes()
                binding.btnAdd.isEnabled = true
                binding.etStockCode.isEnabled = true
            }
        }
    }

    private fun addCode() {
        val code = QuoteParser.normalizeStockCode(binding.etStockCode.text?.toString().orEmpty())
        if (code == null) {
            binding.tvStatus.setText(R.string.status_invalid_code)
            return
        }
        if (watchlist.contains(code)) {
            binding.tvStatus.setText(R.string.status_duplicate_code)
            return
        }
        watchlist.add(code)
        persistWatchlistChange { watchlistStore.add(code) }
        binding.etStockCode.text?.clear()
        renderQuotes()
        if (polling) {
            refreshQuotesNow()
        } else {
            binding.tvStatus.setText(R.string.status_idle)
        }
    }

    private fun removeCode(code: String) {
        watchlist.remove(code)
        persistWatchlistChange { watchlistStore.remove(code) }
        quotes = quotes.filter { it.requestCode != code }
        if (selectedCode == code) {
            selectedCode = null
        }
        renderQuotes()
        if (watchlist.isEmpty()) {
            if (polling) {
                stopPolling()
            }
            return
        }
        if (polling) {
            refreshQuotesNow()
        }
    }

    private fun persistWatchlistChange(action: () -> Unit) {
        executor.execute(action)
    }

    private fun setupQuoteList() {
        quoteAdapter = QuoteListAdapter(
            onClick = { quote ->
                selectedCode = quote.requestCode
                renderQuotes()
            },
            onDelete = { code -> removeCode(code) },
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
            val dragFlags = if (sortMode == QuoteSortMode.CUSTOM) {
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
            persistCustomOrderFromAdapter()
        }
    })

    private fun persistCustomOrderFromAdapter() {
        if (sortMode != QuoteSortMode.CUSTOM) return
        val codes = quoteAdapter.items.map { it.requestCode }
        watchlist.clear()
        watchlist.addAll(codes)
        persistWatchlistChange { watchlistStore.reorder(codes) }
    }

    private fun showSortMenu() {
        PopupMenu(this, binding.btnSort).apply {
            menu.add(0, QuoteSortMode.CHANGE_PERCENT.ordinal, 0, R.string.sort_change)
            menu.add(0, QuoteSortMode.AMOUNT.ordinal, 1, R.string.sort_amount)
            menu.add(0, QuoteSortMode.PRICE.ordinal, 2, R.string.sort_price)
            menu.add(0, QuoteSortMode.CUSTOM.ordinal, 3, R.string.sort_custom)
            setOnMenuItemClickListener { item ->
                val selected = QuoteSortMode.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                sortMode = selected
                sortPreferences.mode = selected
                refreshSortButton()
                renderQuotes()
                true
            }
            show()
        }
    }

    private fun refreshSortButton() {
        binding.btnSort.setText(
            when (sortMode) {
                QuoteSortMode.CUSTOM -> R.string.sort_custom
                QuoteSortMode.CHANGE_PERCENT -> R.string.sort_change
                QuoteSortMode.AMOUNT -> R.string.sort_amount
                QuoteSortMode.PRICE -> R.string.sort_price
            }
        )
    }

    private fun startPolling() {
        if (watchlist.isEmpty()) {
            binding.tvStatus.setText(R.string.status_empty_watchlist)
            return
        }
        intervalMs = QuoteParser.resolveIntervalSeconds(
            binding.etInterval.text?.toString().orEmpty()
        ) * 1000L
        polling = true
        setRunning(true)
        if (TradingSession.isOpen()) {
            binding.tvStatus.setText(R.string.status_running)
        }
        refreshQuotesNow()
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        setRunning(false)
        binding.tvStatus.setText(R.string.status_stopped)
    }

    private fun setRunning(running: Boolean) {
        binding.etInterval.isEnabled = !running
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.root.keepScreenOn = running
    }

    private fun refreshQuotesNow() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun scheduleNextOrFinish(keepErrorStatus: Boolean) {
        if (TradingSession.isOpen()) {
            if (!keepErrorStatus) {
                binding.tvStatus.setText(R.string.status_running)
            }
            handler.postDelayed(pollRunnable, intervalMs)
            return
        }
        polling = false
        handler.removeCallbacks(pollRunnable)
        setRunning(false)
        if (!keepErrorStatus) {
            binding.tvStatus.setText(
                when (TradingSession.phase()) {
                    TradingSession.Phase.PRE_OPEN -> R.string.status_preopen_once
                    TradingSession.Phase.LUNCH -> R.string.status_lunch_once
                    TradingSession.Phase.CLOSED -> R.string.status_closed_once
                    TradingSession.Phase.OPEN -> R.string.status_stopped
                }
            )
        }
    }

    private fun fetchOnceThenSchedule() {
        if (!polling) return
        val codes = watchlist.toList()
        if (codes.isEmpty()) {
            stopPolling()
            return
        }
        executor.execute {
            val result = repository.fetchQuotes(codes)
            handler.post {
                if (!polling) return@post
                result.fold(
                    onSuccess = { latest ->
                        quotes = latest
                        renderQuotes()
                    },
                    onFailure = { error ->
                        val message = error.message.orEmpty()
                        binding.tvStatus.text = if (error is IllegalStateException) {
                            getString(R.string.status_invalid_response)
                        } else {
                            getString(R.string.status_error, message)
                        }
                    }
                )
                scheduleNextOrFinish(keepErrorStatus = result.isFailure)
            }
        }
    }

    private fun renderQuotes() {
        val rows = displayRows()
        if (selectedCode != null && rows.none { it.requestCode == selectedCode }) {
            selectedCode = null
        }
        if (selectedCode == null && quotes.isNotEmpty()) {
            selectedCode = quotes.first().requestCode
        }
        quoteAdapter.submit(rows, selectedCode, sortMode == QuoteSortMode.CUSTOM)
        bindSelectedCard()
    }

    private fun displayRows(): List<QuoteSnapshot> {
        val byCode = quotes.associateBy { it.requestCode }
        val rows = watchlist.map { code ->
            byCode[code] ?: QuoteSnapshot(
                requestCode = code,
                name = "--",
                price = "--",
                changePercent = "",
                fields = emptyList()
            )
        }
        return QuoteSorter.sort(rows, sortMode, watchlist)
    }

    private fun bindSelectedCard() {
        val card = binding.quoteCard
        val quote = quotes.find { it.requestCode == selectedCode }
        if (quote == null) {
            card.tvCardPlaceholder.visibility = View.VISIBLE
            card.llCardContent.visibility = View.GONE
            return
        }
        val data = QuoteCardMapper.from(quote)
        card.tvCardPlaceholder.visibility = View.GONE
        card.llCardContent.visibility = View.VISIBLE
        card.tvCardName.text = data.name
        card.tvCardCode.text = data.code
        card.tvCardPrice.text = data.price
        card.tvCardChangePercent.text = data.changePercent
        card.tvCardChange.text = data.change
        applyChangeColor(card.tvCardChangePercent, quote.changePercent)
        applyChangeColor(card.tvCardChange, quote.changePercent)
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
        card.tvRawDetail.visibility = if (rawExpanded) View.VISIBLE else View.GONE
        card.tvToggleRaw.setText(if (rawExpanded) R.string.hide_raw_fields else R.string.show_raw_fields)
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

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        binding.root.keepScreenOn = false
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
