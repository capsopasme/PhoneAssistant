package com.capsopasme.assistant.fx

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * While time is stopped the live status bar goes away: the frozen frame has its own copy (with
 * the clock stopped), and the real one drawn over it would ignore the effect.
 */
fun Activity.setStatusBarHidden(hidden: Boolean) {
    val controller = window.insetsController ?: return
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsets.Type.statusBars())
    } else {
        controller.show(WindowInsets.Type.statusBars())
    }
}

/**
 * Frees a frozen frame once nothing can be drawing it any more (the render thread may still be
 * finishing the last frame that used it).
 */
fun recycleLater(vararg bitmaps: Bitmap?) {
    val list = bitmaps.filterNotNull()
    if (list.isEmpty()) return
    Handler(Looper.getMainLooper()).postDelayed({ list.forEach { if (!it.isRecycled) it.recycle() } }, 1_000)
}
