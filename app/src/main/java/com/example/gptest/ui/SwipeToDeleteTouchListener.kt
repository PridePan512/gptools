package com.example.gptest.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class SwipeToDeleteTouchListener(
    private val content: View,
    private val onClick: () -> Unit,
    private val onDelete: () -> Unit,
    private val onLongPress: (() -> Unit)? = null,
    private val isLongPressEnabled: () -> Boolean = { onLongPress != null }
) : View.OnTouchListener {

    private val touchSlop = ViewConfiguration.get(content.context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    private var decided = false
    private var deleted = false
    private var longPressed = false

    private val longPressRunnable = Runnable {
        if (!swiping && !deleted) {
            longPressed = true
            onLongPress?.invoke()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                swiping = false
                decided = false
                deleted = false
                longPressed = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (onLongPress != null && isLongPressEnabled()) {
                    view.removeCallbacks(longPressRunnable)
                    view.postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressed) {
                    return false
                }
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (!decided) {
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return true
                    }
                    decided = true
                    view.removeCallbacks(longPressRunnable)
                    if (abs(dx) > abs(dy) && dx < 0) {
                        swiping = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                }
                if (swiping) {
                    content.translationX = dx.coerceAtMost(0f)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressed) {
                    return false
                }
                if (swiping) {
                    val threshold = view.width * 0.4f
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        content.translationX <= -threshold &&
                        !deleted
                    ) {
                        deleted = true
                        onDelete()
                    } else {
                        content.animate().translationX(0f).setDuration(120).start()
                    }
                    return true
                }
                content.translationX = 0f
                if (event.actionMasked == MotionEvent.ACTION_UP && !decided) {
                    onClick()
                }
                return true
            }
        }
        return false
    }

    fun reset(view: View) {
        view.removeCallbacks(longPressRunnable)
        swiping = false
        decided = false
        deleted = false
        longPressed = false
        content.animate().setListener(null).withEndAction(null).cancel()
        content.translationX = 0f
    }
}
