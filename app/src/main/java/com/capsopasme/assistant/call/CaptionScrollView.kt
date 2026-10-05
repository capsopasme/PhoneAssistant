package com.capsopasme.assistant.call

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** A ScrollView that grows with its text up to [maxHeightFraction] of the screen, then scrolls */
class CaptionScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    var maxHeightFraction = 0.3f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }

    /** keep the newest words in view while the answer streams in */
    fun scrollToEnd() {
        post {
            val child: View = getChildAt(0) ?: return@post
            smoothScrollTo(0, (child.height - height).coerceAtLeast(0))
        }
    }
}
