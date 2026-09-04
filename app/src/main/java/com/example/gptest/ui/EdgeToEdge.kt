package com.example.gptest.ui

import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

fun ComponentActivity.prepareEdgeToEdge() {
    enableEdgeToEdge()
}

fun applyEdgeToEdgeInsets(
    root: View,
    toolbar: View,
    content: View,
    fab: View? = null
) {
    val contentBottom = content.paddingBottom
    val fabMargin = fab?.layoutParams as? MarginLayoutParams
    val fabBottom = fabMargin?.bottomMargin ?: 0
    val fabEnd = fabMargin?.marginEnd ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val systemBars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val bottom = maxOf(systemBars.bottom, ime.bottom)
        toolbar.updatePadding(
            left = systemBars.left,
            top = systemBars.top,
            right = systemBars.right
        )
        content.updatePadding(
            left = systemBars.left,
            right = systemBars.right,
            bottom = contentBottom + bottom
        )
        fab?.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = fabBottom + systemBars.bottom
            marginEnd = fabEnd + systemBars.right
        }
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
