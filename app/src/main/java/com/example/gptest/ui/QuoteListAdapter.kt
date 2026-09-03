package com.example.gptest.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.gptest.R
import com.example.gptest.business.QuoteParser
import com.example.gptest.business.QuoteSnapshot

data class QuoteRow(
    val quote: QuoteSnapshot,
    val selected: Boolean,
    val dragEnabled: Boolean
)

class QuoteDiffCallback(
    private val oldList: List<QuoteRow>,
    private val newList: List<QuoteRow>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].quote.requestCode ==
            newList[newItemPosition].quote.requestCode
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}

class QuoteListAdapter(
    private val onClick: (QuoteSnapshot) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onStartDrag: (ViewHolder) -> Unit,
    private val applyChangeColor: (TextView, String) -> Unit
) : RecyclerView.Adapter<QuoteListAdapter.ViewHolder>() {

    val items = mutableListOf<QuoteRow>()
    var selectedCode: String? = null
        private set
    var dragEnabled: Boolean = false
        private set

    fun submit(rows: List<QuoteSnapshot>, selected: String?, dragEnabled: Boolean) {
        val newItems = rows.map { quote ->
            QuoteRow(
                quote = quote,
                selected = quote.requestCode == selected,
                dragEnabled = dragEnabled
            )
        }
        val diff = DiffUtil.calculateDiff(QuoteDiffCallback(items.toList(), newItems), true)
        items.clear()
        items.addAll(newItems)
        selectedCode = selected
        this.dragEnabled = dragEnabled
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quote, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.resetSwipe()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val content: View = itemView.findViewById(R.id.quoteRowContent)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCode: TextView = itemView.findViewById(R.id.tvCode)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvRowPrice)
        private val tvChange: TextView = itemView.findViewById(R.id.tvChangePercent)
        private val touchListener = SwipeToDeleteTouchListener(
            content = content,
            onClick = { currentRow()?.let { onClick(it.quote) } },
            onDelete = { currentRow()?.let { onDelete(it.quote.requestCode) } },
            onLongPress = { onStartDrag(this) },
            isLongPressEnabled = { currentRow()?.dragEnabled == true }
        )

        init {
            @SuppressLint("ClickableViewAccessibility")
            content.setOnTouchListener(touchListener)
        }

        fun bind(row: QuoteRow) {
            resetSwipe()
            val quote = row.quote
            tvName.text = quote.name.ifEmpty { "--" }
            tvCode.text = displayCode(quote)
            tvPrice.text = quote.price.ifEmpty { "--" }
            tvChange.text = QuoteParser.formatChangePercent(quote.changePercent)
            applyChangeColor(tvChange, quote.changePercent)
            content.setBackgroundResource(
                if (row.selected) R.drawable.bg_quote_row_selected else R.drawable.bg_quote_row
            )
        }

        fun resetSwipe() {
            touchListener.reset(content)
        }

        private fun currentRow(): QuoteRow? {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return null
            return items.getOrNull(position)
        }
    }

    companion object {
        fun displayCode(quote: QuoteSnapshot): String {
            val fromFields = quote.fields.getOrNull(2)?.trim().orEmpty()
            if (fromFields.isNotEmpty()) return fromFields
            return quote.requestCode.removePrefix("sz").removePrefix("sh").removePrefix("bj")
        }
    }
}
