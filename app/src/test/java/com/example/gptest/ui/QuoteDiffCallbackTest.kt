package com.example.gptest.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.example.gptest.business.QuoteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteDiffCallbackTest {

    @Test
    fun hiddenFieldChange_doesNotDispatchUpdates() {
        val old = listOf(row(price = "21.15", time = "10:00:00"))
        val new = listOf(row(price = "21.15", time = "10:00:05"))
        val recorder = Recorder()
        DiffUtil.calculateDiff(QuoteDiffCallback(old, new), true).dispatchUpdatesTo(recorder)
        assertEquals(0, recorder.changed)
        assertEquals(0, recorder.moved)
        assertEquals(0, recorder.inserted)
        assertEquals(0, recorder.removed)
    }

    @Test
    fun priceChange_dispatchesChangedWithPayload() {
        val old = listOf(row(price = "21.15", time = "10:00:00"))
        val new = listOf(row(price = "21.16", time = "10:00:05"))
        val callback = QuoteDiffCallback(old, new)
        assertTrue(callback.areItemsTheSame(0, 0))
        assertFalse(callback.areContentsTheSame(0, 0))
        assertEquals(QuoteDiffCallback.PAYLOAD_VISUAL, callback.getChangePayload(0, 0))
        val recorder = Recorder()
        DiffUtil.calculateDiff(callback, true).dispatchUpdatesTo(recorder)
        assertEquals(1, recorder.changed)
        assertEquals(listOf(QuoteDiffCallback.PAYLOAD_VISUAL), recorder.payloads)
    }

    @Test
    fun dragEnabledChange_dispatchesChanged() {
        val old = listOf(row(dragEnabled = true), row(code = "sz000002", dragEnabled = true))
        val new = listOf(row(dragEnabled = false), row(code = "sz000002", dragEnabled = false))
        val recorder = Recorder()
        DiffUtil.calculateDiff(QuoteDiffCallback(old, new), true).dispatchUpdatesTo(recorder)
        assertEquals(2, recorder.changed)
        assertEquals(0, recorder.moved)
    }

    @Test
    fun reorder_dispatchesMoveWithoutRebind() {
        val old = listOf(row(code = "sz000001"), row(code = "sz000002"))
        val new = listOf(row(code = "sz000002"), row(code = "sz000001"))
        val recorder = Recorder()
        DiffUtil.calculateDiff(QuoteDiffCallback(old, new), true).dispatchUpdatesTo(recorder)
        assertEquals(1, recorder.moved)
        assertEquals(0, recorder.changed)
    }

    @Test
    fun limitBoardChange_dispatchesChanged() {
        val old = listOf(row(price = "10.00"))
        val new = listOf(row(price = "10.00", limitUp = "10.00"))
        val recorder = Recorder()
        DiffUtil.calculateDiff(QuoteDiffCallback(old, new), true).dispatchUpdatesTo(recorder)
        assertEquals(1, recorder.changed)
    }

    @Test
    fun changeAmountChange_dispatchesChanged() {
        val old = listOf(row(changeAmount = "0.10"))
        val new = listOf(row(changeAmount = "0.12"))
        val recorder = Recorder()
        DiffUtil.calculateDiff(QuoteDiffCallback(old, new), true).dispatchUpdatesTo(recorder)
        assertEquals(1, recorder.changed)
    }

    private fun row(
        code: String = "sz000001",
        name: String = "测试",
        price: String = "10.00",
        change: String = "1.00",
        changeAmount: String = "0.10",
        time: String = "10:00:00",
        dragEnabled: Boolean = false,
        limitUp: String = "",
        limitDown: String = ""
    ): QuoteRow {
        val fields = MutableList(50) { "" }
        fields[1] = name
        fields[2] = code.takeLast(6)
        fields[3] = price
        fields[30] = time
        fields[31] = changeAmount
        fields[32] = change
        fields[47] = limitUp
        fields[48] = limitDown
        return QuoteRow(
            quote = QuoteSnapshot(code, name, price, change, fields),
            dragEnabled = dragEnabled
        )
    }

    private class Recorder : ListUpdateCallback {
        var inserted = 0
        var removed = 0
        var moved = 0
        var changed = 0
        val payloads = mutableListOf<Any?>()

        override fun onInserted(position: Int, count: Int) {
            inserted += count
        }

        override fun onRemoved(position: Int, count: Int) {
            removed += count
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            moved += 1
        }

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            changed += count
            payloads += payload
        }
    }
}
