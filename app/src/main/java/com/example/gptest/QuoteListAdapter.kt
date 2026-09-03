package com.example.gptest

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QuoteListAdapter(
    private val onClick: (QuoteSnapshot) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onStartDrag: (ViewHolder) -> Unit,
    private val applyChangeColor: (TextView, String) -> Unit
) : RecyclerView.Adapter<QuoteListAdapter.ViewHolder>() {

    val items = mutableListOf<QuoteSnapshot>()
    var selectedCode: String? = null
        private set
    var dragEnabled: Boolean = false
        private set

    fun submit(rows: List<QuoteSnapshot>, selected: String?, dragEnabled: Boolean) {
        items.clear()
        items.addAll(rows)
        selectedCode = selected
        this.dragEnabled = dragEnabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quote, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val content: View = itemView.findViewById(R.id.quoteRowContent)
        private val dragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCode: TextView = itemView.findViewById(R.id.tvCode)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvRowPrice)
        private val tvChange: TextView = itemView.findViewById(R.id.tvChangePercent)

        @SuppressLint("ClickableViewAccessibility")
        fun bind(quote: QuoteSnapshot) {
            tvName.text = quote.name.ifEmpty { "--" }
            tvCode.text = displayCode(quote)
            tvPrice.text = quote.price.ifEmpty { "--" }
            tvChange.text = QuoteParser.formatChangePercent(quote.changePercent)
            applyChangeColor(tvChange, quote.changePercent)
            val selected = quote.requestCode == selectedCode
            content.setBackgroundResource(
                if (selected) R.drawable.bg_quote_row_selected else R.drawable.bg_quote_row
            )
            dragHandle.visibility = if (dragEnabled) View.VISIBLE else View.GONE
            dragHandle.setOnTouchListener { _, event ->
                if (dragEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                    true
                } else {
                    false
                }
            }
            content.setOnTouchListener(
                SwipeToDeleteTouchListener(
                    content = content,
                    onClick = { onClick(quote) },
                    onDelete = { onDelete(quote.requestCode) }
                )
            )
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
