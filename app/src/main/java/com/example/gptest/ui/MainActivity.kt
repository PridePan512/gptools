package com.example.gptest.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.business.QuoteSortMode
import com.example.gptest.business.StockCatalog
import com.example.gptest.business.StockCatalogEntry
import com.example.gptest.business.TradingSession
import com.example.gptest.databinding.ActivityMainBinding
import com.example.gptest.ui.alert.AlertNotificationPermission
import com.example.gptest.ui.alert.AlertRulesActivity
import com.example.gptest.ui.alert.AlertWatchlistExtras
import com.example.gptest.ui.alert.RapidAlertThresholds
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var quoteAdapter: QuoteListAdapter
    private var addStockDialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private var settingsIntervalInput: TextInputEditText? = null
    private var watchlistMenuReady = false
    private var detailSheet: QuoteDetailSheet? = null
    private val stockCatalog by lazy { loadStockCatalog() }

    private val lastUpdatedFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(TradingSession.SHANGHAI)

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            QuoteMonitorHolder.get(this),
            AndroidMonitorServiceGateway(this)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyEdgeToEdgeInsets(binding.root, binding.toolbar, binding.content, binding.fabMonitor)
        setupQuoteList()
        binding.btnSort.setOnClickListener { showSortMenu() }
        binding.fabMonitor.setOnClickListener { toggleMonitor() }
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_add)?.isEnabled = viewModel.uiState.value.watchlistLoaded
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_alerts -> {
                openAlerts()
                true
            }
            R.id.action_add -> {
                showAddStockDialog()
                true
            }
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
                            is UiEvent.AddSucceeded -> {
                                addStockDialog?.dismiss()
                                showMessage(getString(R.string.status_added, event.label))
                            }
                            UiEvent.AddEmptyCode -> showMessage(getString(R.string.status_empty_code))
                            UiEvent.AddInvalidCode -> showMessage(getString(R.string.status_invalid_code))
                            UiEvent.AddDuplicateCode -> showMessage(getString(R.string.status_duplicate_code))
                            is UiEvent.OfferUndoDelete -> showUndoSnackbar(event.label)
                        }
                    }
                }
            }
        }
    }

    private fun render(state: MainUiState) {
        if (watchlistMenuReady != state.watchlistLoaded) {
            watchlistMenuReady = state.watchlistLoaded
            invalidateOptionsMenu()
        }
        settingsIntervalInput?.isEnabled = !state.isRunning
        bindFab(state.isRunning)
        binding.root.keepScreenOn = state.isRunning
        binding.btnSort.setText(sortLabel(state.sortMode))
        bindStatus(state.status)
        bindLastUpdated(state.lastUpdatedMs)
        bindShanghaiIndex(state.shanghaiIndex)
        quoteAdapter.submit(state.rows, state.dragEnabled)
        detailSheet?.bind(state)
    }

    private fun bindFab(running: Boolean) {
        binding.fabMonitor.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        binding.fabMonitor.contentDescription = getString(
            if (running) R.string.fab_stop else R.string.fab_start
        )
        if (running) {
            binding.fabMonitor.backgroundTintList = ColorStateList.valueOf(getColor(R.color.quote_up))
            binding.fabMonitor.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
        } else {
            binding.fabMonitor.backgroundTintList = ColorStateList.valueOf(
                getColor(R.color.purple_500)
            )
            binding.fabMonitor.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
        }
    }

    private fun bindStatus(status: QuoteStatus) {
        val text = statusLineText(status)
        if (text == null) {
            binding.tvStatus.visibility = View.GONE
            return
        }
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = text
    }

    private fun bindLastUpdated(ms: Long?) {
        if (ms == null) {
            binding.tvLastUpdated.visibility = View.GONE
            return
        }
        binding.tvLastUpdated.visibility = View.VISIBLE
        binding.tvLastUpdated.text = getString(
            R.string.quote_last_updated,
            lastUpdatedFormatter.format(Instant.ofEpochMilli(ms))
        )
    }

    private fun bindShanghaiIndex(quote: QuoteSnapshot?) {
        val name = quote?.name?.trim()?.takeIf { it.isNotEmpty() && it != "--" }
            ?: getString(R.string.shanghai_index_name)
        val price = quote?.price?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.shanghai_index_placeholder)
        val change = QuoteParser.formatChangePercent(quote?.changePercent.orEmpty())
        binding.tvIndexName.text = name
        binding.tvIndexPrice.text = price
        binding.tvIndexChange.text = change
        applyChangeColor(binding.tvIndexPrice, quote?.changePercent.orEmpty())
        applyChangeColor(binding.tvIndexChange, quote?.changePercent.orEmpty())
    }

    private fun openQuoteDetail(code: String) {
        val sheet = detailSheet ?: QuoteDetailSheet(this) { detailSheet = null }.also { detailSheet = it }
        sheet.show(code)
    }

    private fun toggleMonitor() {
        if (viewModel.uiState.value.isRunning) {
            viewModel.stopPolling()
            return
        }
        AlertNotificationPermission.requestIfNeeded(this)
        viewModel.startPolling(viewModel.uiState.value.intervalSeconds.toString())
    }

    private fun openAlerts() {
        startActivity(
            AlertWatchlistExtras.put(
                Intent(this, AlertRulesActivity::class.java),
                AlertWatchlistExtras.fromQuotes(viewModel.uiState.value.rows)
            )
        )
    }

    private fun showAddStockDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_stock, null)
        val input = view.findViewById<TextInputEditText>(R.id.etAddStockCode)
        val listView = view.findViewById<ListView>(R.id.lvAddStockSuggestions)
        val suggestions = mutableListOf<StockCatalogEntry>()
        val adapter = object : ArrayAdapter<StockCatalogEntry>(
            this,
            R.layout.item_stock_suggestion,
            suggestions
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val itemView = super.getView(position, convertView, parent) as TextView
                itemView.text = getItem(position)?.displayLabel.orEmpty()
                return itemView
            }
        }
        listView.adapter = adapter
        val refreshSuggestions = {
            val hits = stockCatalog.search(input.text?.toString().orEmpty())
            suggestions.clear()
            suggestions.addAll(hits)
            adapter.notifyDataSetChanged()
            listView.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
        }
        val submit = {
            addStockFromQuery(input.text?.toString().orEmpty())
            dismissKeyboard(input)
        }
        input.doAfterTextChanged { refreshSuggestions() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val code = suggestions.getOrNull(position)?.code ?: return@setOnItemClickListener
            viewModel.addCode(code)
            dismissKeyboard(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_stock)
            .setView(view)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                viewModel.uiState.value.watchlistLoaded
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        dialog.setOnDismissListener {
            if (addStockDialog == dialog) addStockDialog = null
        }
        addStockDialog = dialog
        dialog.show()
        input.requestFocus()
    }

    private fun addStockFromQuery(raw: String) {
        val query = raw.trim()
        val code = stockCatalog.resolveAddQuery(query)
        when {
            code != null -> viewModel.addCode(code)
            query.isEmpty() -> viewModel.addCode(raw)
            stockCatalog.search(query).isNotEmpty() -> {
                showMessage(getString(R.string.status_pick_suggestion))
            }
            else -> viewModel.addCode(raw)
        }
    }

    private fun loadStockCatalog(): StockCatalog {
        return try {
            assets.open("stock_catalog.txt").bufferedReader().use { StockCatalog.parse(it.readText()) }
        } catch (_: Exception) {
            StockCatalog(emptyList())
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val intervalInput = view.findViewById<TextInputEditText>(R.id.etSettingsInterval)
        val volumeSurgeInput = view.findViewById<TextInputEditText>(R.id.etVolumeSurge)
        val volumeShrinkInput = view.findViewById<TextInputEditText>(R.id.etVolumeShrink)
        val priceSurgeInput = view.findViewById<TextInputEditText>(R.id.etPriceSurge)
        val priceDropInput = view.findViewById<TextInputEditText>(R.id.etPriceDrop)
        val state = viewModel.uiState.value
        val running = state.isRunning
        val thresholds = state.rapidAlertThresholds
        intervalInput.setText(state.intervalSeconds.toString())
        intervalInput.isEnabled = !running
        volumeSurgeInput.setText(RapidAlertThresholds.formatPercent(thresholds.volumeSurgePercent))
        volumeShrinkInput.setText(RapidAlertThresholds.formatPercent(thresholds.volumeShrinkPercent))
        priceSurgeInput.setText(RapidAlertThresholds.formatPercent(thresholds.priceSurgePercent))
        priceDropInput.setText(RapidAlertThresholds.formatPercent(thresholds.priceDropPercent))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val save = {
            if (!viewModel.uiState.value.isRunning) {
                viewModel.saveInterval(intervalInput.text?.toString().orEmpty())
            }
            viewModel.saveRapidThresholds(
                volumeSurgeInput.text?.toString().orEmpty(),
                volumeShrinkInput.text?.toString().orEmpty(),
                priceSurgeInput.text?.toString().orEmpty(),
                priceDropInput.text?.toString().orEmpty()
            )
            dialog.dismiss()
        }
        priceDropInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        }
        dialog.setOnDismissListener {
            if (settingsDialog == dialog) {
                settingsDialog = null
                settingsIntervalInput = null
            }
        }
        settingsIntervalInput = intervalInput
        settingsDialog = dialog
        dialog.show()
    }

    private fun dismissKeyboard(view: View) {
        view.clearFocus()
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabMonitor)
            .show()
    }

    private fun showUndoSnackbar(label: String) {
        Snackbar.make(
            binding.root,
            getString(R.string.snackbar_deleted, label),
            Snackbar.LENGTH_LONG
        ).setAnchorView(binding.fabMonitor).setAction(R.string.undo) {
            viewModel.undoRemove()
        }.show()
    }

    private fun setupQuoteList() {
        quoteAdapter = QuoteListAdapter(
            onClick = { quote -> openQuoteDetail(quote.requestCode) },
            onDelete = { code -> viewModel.removeCode(code) },
            onStartDrag = { holder ->
                itemTouchHelper.startDrag(holder)
            },
            applyChangeColor = { view, change -> applyChangeColor(view, change) }
        )
        binding.rvQuoteList.layoutManager = LinearLayoutManager(this)
        binding.rvQuoteList.adapter = quoteAdapter
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

    private fun statusLineText(status: QuoteStatus): String? = when (status) {
        QuoteStatus.Idle, QuoteStatus.Running, QuoteStatus.Stopped,
        QuoteStatus.InvalidCode, QuoteStatus.DuplicateCode -> null
        QuoteStatus.EmptyWatchlist -> getString(R.string.status_empty_watchlist)
        QuoteStatus.InvalidResponse -> getString(R.string.status_invalid_response)
        is QuoteStatus.NetworkError -> getString(R.string.status_error, status.message)
        is QuoteStatus.SessionOnce -> when (status.phase) {
            TradingSession.Phase.PRE_OPEN -> getString(R.string.status_preopen_once)
            TradingSession.Phase.LUNCH -> getString(R.string.status_lunch_once)
            TradingSession.Phase.CLOSED -> getString(R.string.status_closed_once)
            TradingSession.Phase.OPEN -> null
        }
    }

    private fun applyChangeColor(view: TextView, changePercent: String) {
        QuoteCardBinder.applyChangeColor(this, view, changePercent)
    }
}
