/*
 * Adapted from the fcitx5-android voice input (VoiceModelManager.kt)
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package com.capsopasme.assistant.asr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import com.capsopasme.assistant.asr.archive.TarExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Downloads / imports / copies / deletes speech models, in the main process.
 * Models live in app-private internal storage (`filesDir/asr-models/<dirName>`):
 * QNN needs real file paths and external storage is mounted noexec.
 *
 * One background thread, only alive while a job runs (it times out when idle).
 */
object ModelManager {

    sealed interface State {
        data object NotInstalled : State
        data class Installed(val bytes: Long) : State
        data class Working(val what: String, val done: Long, val total: Long) : State
        data class Failed(val message: String) : State
    }

    fun interface Listener {
        fun onStateChanged(model: SpeechModel, state: State)
    }

    const val VAD_FILE_NAME = "silero_vad.onnx"
    private const val TAG = "ModelManager"
    private const val MB = 1024L * 1024L
    private const val FREE_SPACE_MARGIN = 256 * MB

    /** fcitx5-android (release / debug) with the built-in voice input */
    private val FCITX_PACKAGES = listOf("org.fcitx.fcitx5.android", "org.fcitx.fcitx5.android.debug")

    private val main = Handler(Looper.getMainLooper())

    // core thread times out, so no thread stays parked when nothing is being downloaded
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 10, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }

    @Volatile
    private var cancelled = false

    @Volatile
    private var busy: SpeechModel? = null

    private val states = HashMap<SpeechModel, State>()
    private var listener: Listener? = null

    fun rootDir(ctx: Context) = File(ctx.filesDir, "asr-models")
    fun modelDir(ctx: Context, model: SpeechModel) = File(rootDir(ctx), model.dirName)

    fun isInstalled(ctx: Context, model: SpeechModel) =
        model.requiredFiles.all { File(modelDir(ctx, model), it).let { f -> f.isFile && f.length() > 0 } }

    val isBusy: Boolean get() = busy != null

    /** main thread only; pass null to stop listening (e.g. in onDestroy) */
    fun setListener(l: Listener?) {
        listener = l
    }

    fun state(model: SpeechModel): State? = states[model]

    private fun setState(model: SpeechModel, state: State) {
        main.post {
            states[model] = state
            listener?.onStateChanged(model, state)
        }
    }

    /** Re-scan disk (main thread), keeping running / failed states */
    fun refresh(ctx: Context) {
        if (!isBusy) cleanupStale(ctx)
        SpeechModel.entries.forEach { m ->
            if (busy == m) return@forEach
            val disk = diskState(ctx, m)
            if (states[m] is State.Failed && disk is State.NotInstalled) return@forEach
            states[m] = disk
            listener?.onStateChanged(m, disk)
        }
    }

    private fun diskState(ctx: Context, model: SpeechModel): State =
        if (isInstalled(ctx, model)) State.Installed(dirSize(modelDir(ctx, model))) else State.NotInstalled

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    /** temp directories left by a job interrupted by process death, models this version doesn't know */
    private fun cleanupStale(ctx: Context) {
        rootDir(ctx).listFiles()?.forEach { f ->
            val stale = f.name.startsWith(".tmp") ||
                    (f.isDirectory && SpeechModel.fromDirName(f.name) == null)
            if (stale) f.deleteRecursively()
        }
    }

    private fun checkFreeSpace(dir: File, bytes: Long) {
        if (bytes <= 0) return
        dir.mkdirs()
        val needed = bytes + bytes / 4 + FREE_SPACE_MARGIN
        val available = StatFs(dir.path).availableBytes
        if (available < needed) {
            throw IOException("存储空间不足：需要 ${needed / MB} MB，可用 ${available / MB} MB")
        }
    }

    fun cancel() {
        cancelled = true
    }

    fun delete(ctx: Context, model: SpeechModel) {
        if (busy == model) return
        modelDir(ctx, model).deleteRecursively()
        states[model] = State.NotInstalled
        listener?.onStateChanged(model, State.NotInstalled)
    }

    private inline fun runJob(ctx: Context, model: SpeechModel, crossinline block: (tmp: File) -> Unit) {
        if (busy != null) return
        busy = model
        cancelled = false
        val appCtx = ctx.applicationContext
        executor.execute {
            val tmp = File(rootDir(appCtx), ".tmp-${model.dirName}")
            val result: State = try {
                tmp.deleteRecursively()
                block(tmp)
                installFromTmp(appCtx, tmp, model)
                diskState(appCtx, model)
            } catch (e: Throwable) {
                Log.w(TAG, "model job failed", e)
                if (cancelled) diskState(appCtx, model)
                else State.Failed(e.localizedMessage ?: e.javaClass.simpleName)
            } finally {
                tmp.deleteRecursively()
            }
            // not busy any more before the final state reaches the UI: otherwise the
            // download button could keep showing "取消" after the job ended
            busy = null
            setState(model, result)
        }
    }

    /**
     * @param urlPrefix optional mirror prefix, e.g. "https://ghfast.top/";
     * "{url}" inside it is replaced by the GitHub url instead
     */
    fun download(ctx: Context, model: SpeechModel, urlPrefix: String) {
        val url = urlPrefix.trim().let { p ->
            when {
                p.isEmpty() -> model.defaultUrl
                p.contains("{url}") -> p.replace("{url}", model.defaultUrl)
                else -> p + model.defaultUrl
            }
        }
        val appCtx = ctx.applicationContext
        runJob(ctx, model) { tmp ->
            val estimate = model.downloadSizeMb * MB
            setState(model, State.Working("下载中", 0, estimate))
            checkFreeSpace(rootDir(appCtx), estimate)
            val conn = openConnection(url)
            try {
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: estimate
                conn.inputStream.use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        setState(model, State.Working("下载中", read, total))
                    }
                    TarExtractor.extractTarBz2(
                        counting, tmp,
                        filter = { model.shouldExtract(it) },
                        isCancelled = { cancelled }
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Import a sherpa-onnx `.tar.bz2` model archive downloaded elsewhere */
    fun importArchive(ctx: Context, model: SpeechModel, uri: Uri) {
        val appCtx = ctx.applicationContext
        runJob(ctx, model) { tmp ->
            val total = appCtx.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { it.length }?.takeIf { it > 0 } ?: -1L
            checkFreeSpace(rootDir(appCtx), total)
            setState(model, State.Working("导入中", 0, total))
            val input = appCtx.contentResolver.openInputStream(uri) ?: throw IOException("无法打开文件")
            input.use { raw ->
                val counting = CountingInputStream(raw) { read ->
                    setState(model, State.Working("导入中", read, total))
                }
                TarExtractor.extractTarBz2(
                    counting, tmp,
                    filter = { model.shouldExtract(it) },
                    isCancelled = { cancelled }
                )
            }
        }
    }

    /**
     * Copy an already installed model out of fcitx5-android's private storage, with root.
     * Files are streamed through `su -c cat` and written by this app, so ownership and the
     * SELinux label are this app's own (no chown / restorecon needed).
     */
    fun copyFromFcitx(ctx: Context, model: SpeechModel) {
        val appCtx = ctx.applicationContext
        runJob(ctx, model) { tmp ->
            setState(model, State.Working("从小企鹅输入法复制", 0, model.downloadSizeMb * MB))
            checkFreeSpace(rootDir(appCtx), model.downloadSizeMb * MB)
            val srcDir = FCITX_PACKAGES
                .map { "/data/data/$it/files/voice-models/${model.dirName}" }
                .firstOrNull { rootFileExists("$it/${model.requiredFiles.first()}") }
                ?: throw IOException("小企鹅输入法里没有找到这个模型（或没有授予 root）")
            val dest = File(tmp, model.dirName).apply { mkdirs() }
            var copied = 0L
            for (rel in model.requiredFiles + model.testWav) {
                if (cancelled) throw InterruptedException("cancelled")
                val out = File(dest, rel)
                out.parentFile?.mkdirs()
                val ok = rootCat("$srcDir/$rel", out) { n ->
                    setState(model, State.Working("从小企鹅输入法复制", copied + n, model.downloadSizeMb * MB))
                }
                // the test wav is optional, model files are not
                if (!ok && rel != model.testWav) throw IOException("复制 $rel 失败")
                copied += out.length()
            }
        }
    }

    private fun installFromTmp(ctx: Context, tmp: File, model: SpeechModel) {
        val extracted = File(tmp, model.dirName)
        val missing = model.requiredFiles.filterNot { File(extracted, it).isFile }
        if (missing.isNotEmpty()) throw IOException("压缩包里缺少：${missing.joinToString()}（模型选错了？）")
        val dest = modelDir(ctx, model)
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!extracted.renameTo(dest)) throw IOException("无法移动模型文件")
    }

    // ---------------------------------------------------------------------------------------------

    private fun rootFileExists(path: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", "test -f '$path'").redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /** @return false if the file couldn't be read */
    private fun rootCat(path: String, out: File, progress: (Long) -> Unit): Boolean {
        val p = ProcessBuilder("su", "-c", "cat '$path' 2>/dev/null").start()
        try {
            var total = 0L
            var lastReport = 0L
            p.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled) throw InterruptedException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total - lastReport >= 4 * MB) {
                            lastReport = total
                            progress(total)
                        }
                    }
                    output.fd.sync()
                }
            }
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && total > 0
        } finally {
            p.destroy()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        var current = URL(url)
        // follow redirects manually, HttpURLConnection refuses cross-host ones
        repeat(8) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "PhoneAssistant")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code 没有跳转地址")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code：$current")
            }
            return conn
        }
        throw IOException("跳转次数过多")
    }

    private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) :
        FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        private fun advance(n: Long) {
            if (n <= 0) return
            count += n
            if (count - lastReport >= 512 * 1024) {
                lastReport = count
                onRead(count)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { advance(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { advance(it) }
    }
}
