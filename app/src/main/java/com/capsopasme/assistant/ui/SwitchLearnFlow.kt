package com.capsopasme.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.capsopasme.assistant.agent.LearnedSwitches
import com.capsopasme.assistant.agent.RootShell

/**
 * Teaches the assistant one of this phone's own switches ([LearnedSwitches]): the user flips the
 * switch in the control center off → on → off, the settings are read after each step, and the keys
 * that followed it are kept. Then it can be tried right away, and forgotten if it does nothing.
 */
class SwitchLearnFlow(private val activity: Activity, private val onChanged: () -> Unit) {

    private val alive get() = !activity.isFinishing && !activity.isDestroyed

    fun start() {
        val keys = LearnedSwitches.LEARNABLE.keys.toList()
        val labels = keys.map { k ->
            LearnedSwitches.LEARNABLE.getValue(k) + if (LearnedSwitches.get(activity, k) != null) "（已学会）" else ""
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("教它认哪个开关？")
            .setItems(labels) { _, i -> pick(keys[i]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(setting: String) = LearnedSwitches.LEARNABLE.getValue(setting)

    private fun pick(setting: String) {
        val learned = LearnedSwitches.get(activity, setting) ?: return step(setting, 1, emptyList())
        AlertDialog.Builder(activity)
            .setTitle(label(setting))
            .setMessage("已经学会，说“打开/关闭${label(setting)}”时按这个方式改：\n\n" + learned.joinToString("\n"))
            .setPositiveButton("试一下") { _, _ -> tryIt(setting, learned) }
            .setNeutralButton("重新学习") { _, _ -> step(setting, 1, emptyList()) }
            .setNegativeButton("忘掉") { _, _ -> forget(setting) }
            .show()
    }

    private fun forget(setting: String) {
        LearnedSwitches.remove(activity, setting)
        toast("已忘掉，${label(setting)}恢复用默认方式")
        onChanged()
    }

    /** the three readings: off, on, off again */
    private fun step(setting: String, n: Int, readings: List<Map<String, String>>) {
        val l = label(setting)
        val text = when (n) {
            1 -> "下拉控制中心，先把「$l」关掉（本来就是关着的就不用动），再回到这里点“下一步”。\n\n过程中别动其他设置。"
            2 -> "现在在控制中心打开「$l」，再回来点“下一步”。"
            else -> "最后再把「$l」关掉，点“完成”。"
        }
        AlertDialog.Builder(activity)
            .setTitle("学习「$l」（$n/3）")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(if (n < 3) "下一步" else "完成") { _, _ ->
                read { snapshot ->
                    val all = readings + snapshot
                    if (n < 3) step(setting, n + 1, all) else finish(setting, all)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** reads the settings off the main thread, then [then] on it; root is needed */
    private fun read(then: (Map<String, String>) -> Unit) {
        val wait = AlertDialog.Builder(activity).setMessage("正在读取系统设置…").setCancelable(false).show()
        Thread({
            val snapshot = LearnedSwitches.snapshot()
            activity.runOnUiThread {
                wait.dismiss()
                if (!alive) return@runOnUiThread
                if (snapshot == null) toast("读取系统设置失败：需要 root（在 KernelSU 管理器里授权）")
                else then(snapshot)
            }
        }, "learn-switch").start()
    }

    private fun finish(setting: String, readings: List<Map<String, String>>) {
        val l = label(setting)
        when (val r = LearnedSwitches.diff(readings[0], readings[1], readings[2])) {
            is LearnedSwitches.Outcome.Learned -> {
                LearnedSwitches.put(activity, setting, r.entries)
                onChanged()
                AlertDialog.Builder(activity)
                    .setTitle("学会了「$l」")
                    .setMessage("跟着它变化的设置项：\n\n" + r.entries.joinToString("\n") +
                            "\n\n以后说“打开/关闭$l”就按这个方式改。建议试一下，确认真的有效果。")
                    .setPositiveButton("试一下") { _, _ -> tryIt(setting, r.entries) }
                    .setNegativeButton("好", null)
                    .show()
            }
            is LearnedSwitches.Outcome.Failed -> AlertDialog.Builder(activity)
                .setTitle("没学会「$l」")
                .setMessage(r.reason)
                .setPositiveButton("好", null)
                .show()
        }
    }

    /** turns it on the learned way and asks whether that did anything */
    private fun tryIt(setting: String, entries: List<LearnedSwitches.Entry>) {
        val l = label(setting)
        write(entries, true) { ok ->
            if (!ok) return@write toast("执行失败：需要 root")
            AlertDialog.Builder(activity)
                .setTitle("试一下「$l」")
                .setMessage("已经按学到的方式打开了「$l」。屏幕效果和控制中心里的开关变了吗？")
                .setCancelable(false)
                .setPositiveButton("变了，再关掉") { _, _ ->
                    write(entries, false) { toast(if (it) "好的，以后就这样开关「$l」" else "关闭失败") }
                }
                .setNegativeButton("没变化") { _, _ ->
                    AlertDialog.Builder(activity)
                        .setMessage("光改这几个设置项触发不了「$l」，建议忘掉，继续用默认方式。")
                        .setPositiveButton("忘掉") { _, _ ->
                            write(entries, false) { forget(setting) }
                        }
                        .setNegativeButton("保留", null)
                        .show()
                }
                .show()
        }
    }

    private fun write(entries: List<LearnedSwitches.Entry>, on: Boolean, then: (Boolean) -> Unit) {
        Thread({
            val r = RootShell.run(LearnedSwitches.writeCommand(entries, on), 30_000)
            activity.runOnUiThread { if (alive) then(r.ok) }
        }, "learn-switch-write").start()
    }

    private fun toast(text: String) {
        if (alive) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
