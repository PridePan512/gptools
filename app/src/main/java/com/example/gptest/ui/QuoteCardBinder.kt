package com.example.gptest.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.example.gptest.R
import com.example.gptest.business.QuoteCard
import com.example.gptest.business.QuoteParser
import com.example.gptest.databinding.ViewQuoteCardBinding

object QuoteCardBinder {

    fun bind(
        context: Context,
        card: ViewQuoteCardBinding,
        data: QuoteCard?,
        rawExpanded: Boolean
    ) {
        if (data == null) {
            card.llCardContent.visibility = View.GONE
            return
        }
        card.llCardContent.visibility = View.VISIBLE
        card.tvCardName.text = data.name
        card.tvCardCode.text = data.code
        card.tvCardPrice.text = data.price
        card.tvCardChangePercent.text = data.changePercent
        card.tvCardChange.text = data.change
        applyChangeColor(context, card.tvCardPrice, data.changePercent)
        applyChangeColor(context, card.tvCardChangePercent, data.changePercent)
        applyChangeColor(context, card.tvCardChange, data.changePercent)
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
            context.getString(R.string.level_bid, index + 1, level.price, level.volume)
        }.joinToString("\n")
        card.tvAsks.text = data.asks.mapIndexed { index, level ->
            context.getString(R.string.level_ask, index + 1, level.price, level.volume)
        }.joinToString("\n")
        card.tvRawDetail.text = data.rawDetail
        card.tvRawDetail.visibility = if (rawExpanded) View.VISIBLE else View.GONE
        card.tvToggleRaw.setText(
            if (rawExpanded) R.string.hide_raw_fields else R.string.show_raw_fields
        )
    }

    fun applyChangeColor(context: Context, view: TextView, changePercent: String) {
        val changeValue = QuoteParser.changePercentValue(changePercent)
        val changeColor = when {
            changeValue == null || changeValue == 0.0 -> R.color.quote_flat
            changeValue > 0 -> R.color.quote_up
            else -> R.color.quote_down
        }
        view.setTextColor(context.getColor(changeColor))
    }
}
