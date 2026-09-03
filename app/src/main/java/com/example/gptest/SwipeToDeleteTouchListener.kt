package com.example.gptest

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class SwipeToDeleteTouchListener(
    private val content: View,
    private val onClick: () -> Unit,
    private val onDelete: () -> Unit
) : View.OnTouchListener {

    private val touchSlop = ViewConfiguration.get(content.context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var swiping = false
    private var decided = false
    private var deleted = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                swiping = false
                decided = false
                deleted = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                if (!decided) {
                    if (abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return true
                    }
                    decided = true
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
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (swiping) {
                    val threshold = view.width * 0.4f
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        content.translationX <= -threshold &&
                        !deleted
                    ) {
                        deleted = true
                        content.animate()
                            .translationX(-view.width.toFloat())
                            .setDuration(120)
                            .withEndAction(onDelete)
                            .start()
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
}
