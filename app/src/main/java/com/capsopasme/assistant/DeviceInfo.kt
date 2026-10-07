package com.capsopasme.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Which system the phone runs. The assistant was first written for LineageOS; on ColorOS some
 * things differ (how the assistant is invoked, background limits, OEM switches), and the model
 * should know which one it is on.
 */
object DeviceInfo {

    @Volatile
    private var cached: Rom? = null

    class Rom(
        /** e.g. "ColorOS 16", "LineageOS 23.2", "Android 16" */
        val name: String,
        val isColorOs: Boolean,
    )

    /** Blocking the first time (one short getprop): fine on a worker thread, ~20 ms on the main one */
    fun rom(context: Context): Rom = cached ?: detect(context.applicationContext).also { cached = it }

    private fun detect(context: Context): Rom {
        val oplus = prop("ro.build.version.oplusrom")
        if (oplus.isNotEmpty() || installed(context, "com.oplus.battery") || installed(context, "com.coloros.safecenter")) {
            // "V16.0.0" -> 16
            val major = Regex("(\\d+)").find(oplus)?.groupValues?.get(1)
            return Rom(if (major != null) "ColorOS $major" else "ColorOS", true)
        }
        val lineage = prop("ro.lineage.build.version").ifEmpty { prop("ro.lineage.version") }
        if (lineage.isNotEmpty()) return Rom("LineageOS ${lineage.substringBefore('-')}", false)
        return Rom("Android ${Build.VERSION.RELEASE}", false)
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** a system property, "" if unset or unreadable (getprop needs no permission) */
    private fun prop(key: String): String = try {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        try {
            val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            p.waitFor(2, TimeUnit.SECONDS)
            out
        } finally {
            p.destroy()
        }
    } catch (_: Exception) {
        ""
    }
}
