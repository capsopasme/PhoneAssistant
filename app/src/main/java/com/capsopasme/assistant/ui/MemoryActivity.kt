package com.capsopasme.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.capsopasme.assistant.R
import com.capsopasme.assistant.memory.MemoryStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What 噜噜 remembers: every fact and call summary, each deletable, and a way to clear it all */
class MemoryActivity : Activity() {

    private lateinit var list: LinearLayout
    private val date = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(list)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        text("噜噜的记忆", 26f, R.color.text_primary, bold = true)
        text(
            "噜噜在通话里会自然地用到这些。点“删除”去掉一条；通话里说“忘掉某某事”也可以。每次挂断后联网时会自动整理新的记忆。",
            13f, R.color.text_secondary, top = 8,
        )

        val facts = MemoryStore.facts(this)
        section("关于你（${facts.size}）")
        if (facts.isEmpty()) text("还没有", 15f, R.color.text_secondary, top = 6)
        for (f in facts) {
            row(f.text) {
                MemoryStore.removeFacts(this, listOf(f.id))
                render()
            }
        }

        val calls = MemoryStore.calls(this).asReversed()
        section("聊过的通话（${calls.size}）")
        if (calls.isEmpty()) text("还没有", 15f, R.color.text_secondary, top = 6)
        val zone = ZoneId.systemDefault()
        for (c in calls) {
            val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(c.time), zone).format(date)
            row("$time\n${c.text}") {
                MemoryStore.removeCall(this, c.time)
                render()
            }
        }

        val pending = MemoryStore.pendingFiles(this).size
        if (pending > 0) text("还有 $pending 次通话等联网后整理。", 13f, R.color.text_secondary, top = 12)

        if (facts.isNotEmpty() || calls.isNotEmpty() || pending > 0) {
            val clear = Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
                text = "全部清空"
                setTextColor(getColor(R.color.error))
                setOnClickListener { confirmClear() }
            }
            list.addView(clear, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) })
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("清空噜噜记得的所有事？清空后不能恢复。")
            .setPositiveButton("清空") { _, _ ->
                MemoryStore.clear(this)
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun section(title: String) {
        text(title, 15f, R.color.accent, bold = true, top = 28)
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false, top: Int = 0) {
        val v = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(getColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        list.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) })
    }

    /** one memory with a delete button */
    private fun row(s: String, onDelete: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getColor(R.color.text_primary))
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this, null, 0, android.R.style.Widget_DeviceDefault_Button_Borderless_Colored).apply {
            text = "删除"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onDelete() }
        })
        list.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        // a hairline between rows
        list.addView(View(this).apply { setBackgroundColor(getColor(R.color.input_bg)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
