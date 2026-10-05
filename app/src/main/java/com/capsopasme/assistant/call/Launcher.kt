package com.capsopasme.assistant.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.util.Log
import com.capsopasme.assistant.agent.RootShell

/**
 * Starts activities for the call while its screen isn't in view (screen off, another app on
 * top). Android blocks activity starts from the background then, a foreground service doesn't
 * change that; `am start` run with root does not go through those checks.
 */
object Launcher {

    private const val TAG = "Launcher"

    /**
     * A real phone call goes through Telecom, which needs no activity of ours.
     * @return true if [intent] was a call and has been placed
     */
    fun placeCallIfCall(context: Context, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_CALL) return false
        val uri = intent.data ?: return false
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            context.getSystemService(TelecomManager::class.java).placeCall(uri, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "placeCall failed", e)
            false
        }
    }

    /** Blocking (runs su): call it off the main thread. @return true if `am start` succeeded */
    fun startAsRoot(intent: Intent): Boolean {
        val args = amStartArgs(intent) ?: return false
        val r = RootShell.run("am start --user current " + args.joinToString(" ") { quote(it) }, 8_000)
        // am prints "Error: ..." with exit code 0 for some failures
        val ok = r.ok && r.output.lines().none { it.trimStart().startsWith("Error") }
        if (!ok) Log.w(TAG, "am start failed (${r.code}): ${r.output.take(200)}")
        return ok
    }

    /** the intent as `am start` arguments; null if an extra can't be expressed */
    fun amStartArgs(intent: Intent): List<String>? {
        val a = ArrayList<String>()
        intent.action?.let { a += listOf("-a", it) }
        intent.data?.let { a += listOf("-d", it.toString()) }
        intent.type?.let { a += listOf("-t", it) }
        intent.categories?.forEach { a += listOf("-c", it) }
        intent.component?.let { a += listOf("-n", it.flattenToString()) }
            ?: intent.`package`?.let { a += listOf("-p", it) }
        val flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        a += listOf("-f", "0x" + Integer.toHexString(flags))
        val extras = intent.extras
        if (extras != null) for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            when (val v = extras.get(key)) {
                null -> a += listOf("--esn", key)
                is String -> a += listOf("--es", key, v)
                is Boolean -> a += listOf("--ez", key, v.toString())
                is Int -> a += listOf("--ei", key, v.toString())
                is Long -> a += listOf("--el", key, v.toString())
                is Float -> a += listOf("--ef", key, v.toString())
                is Double -> a += listOf("--ed", key, v.toString())
                is IntArray -> a += listOf("--eia", key, v.joinToString(","))
                is ArrayList<*> -> when {
                    v.all { it is Int } -> a += listOf("--eial", key, v.joinToString(","))
                    // a comma inside a value can't be written with --esal
                    v.all { it is String && !it.contains(',') } -> a += listOf("--esal", key, v.joinToString(","))
                    else -> return null
                }
                is CharSequence -> a += listOf("--es", key, v.toString())
                else -> {
                    Log.w(TAG, "extra $key (${v.javaClass.simpleName}) can't be passed to am start")
                    return null
                }
            }
        }
        return a
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
