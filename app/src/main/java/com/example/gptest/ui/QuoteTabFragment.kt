package com.example.gptest.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.business.QuoteSnapshot
import com.example.gptest.databinding.FragmentQuoteTabBinding
import kotlinx.coroutines.launch
import java.util.Collections

class QuoteTabFragment : Fragment() {

    private var _binding: FragmentQuoteTabBinding? = null
    private val binding get() = _binding!!
    private val tabId: String by lazy { requireArguments().getString(ARG_TAB_ID).orEmpty() }
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var quoteAdapter: QuoteListAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuoteTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        quoteAdapter = QuoteListAdapter(
            onClick = { quote -> (activity as? QuoteTabHost)?.openQuoteDetail(quote.requestCode) },
            onLongPress = { quote -> (activity as? QuoteTabHost)?.showQuoteActions(tabId, quote) },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
            applyChangeColor = { textView, change ->
                (activity as? QuoteTabHost)?.applyQuoteChangeColor(textView, change)
            }
        )
        binding.rvQuoteList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQuoteList.adapter = quoteAdapter
        itemTouchHelper = ItemTouchHelper(dragCallback())
        itemTouchHelper.attachToRecyclerView(binding.rvQuoteList)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val tab = state.tabs.find { it.id == tabId }
                    quoteAdapter.submit(tab?.rows.orEmpty(), state.dragEnabled)
                }
            }
        }
    }

    override fun onDestroyView() {
        itemTouchHelper.attachToRecyclerView(null)
        _binding = null
        super.onDestroyView()
    }

    private fun dragCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
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
                    viewModel.reorder(quoteAdapter.items.map { it.quote.requestCode }, tabId)
                }
            }
        }
    }

    companion object {
        private const val ARG_TAB_ID = "tab_id"

        fun newInstance(tabId: String): QuoteTabFragment {
            return QuoteTabFragment().apply {
                arguments = Bundle().apply { putString(ARG_TAB_ID, tabId) }
            }
        }
    }
}

interface QuoteTabHost {
    fun openQuoteDetail(code: String)
    fun applyQuoteChangeColor(view: TextView, changePercent: String)
    fun showQuoteActions(tabId: String, quote: QuoteSnapshot)
}
