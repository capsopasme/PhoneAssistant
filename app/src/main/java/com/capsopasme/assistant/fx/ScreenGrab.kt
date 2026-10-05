package com.capsopasme.assistant.fx

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame of what is on the display right now, for the time-stop effect: root `screencap`
 * writes raw RGBA to its stdout, read straight into memory. Nothing is written to storage, and the
 * frame lives in GPU memory ([Bitmap.Config.HARDWARE]) until the effect releases it.
 *
 * Blocking (about 0.1–0.3 s): call it off the main thread.
 */
object ScreenGrab {

    private const val TAG = "ScreenGrab"

    /** screencap's pixel formats with 4 bytes per pixel in RGBA order */
    private const val RGBA_8888 = 1
    private const val RGBX_8888 = 2

    class Shot(
        /** full resolution */
        val full: Bitmap,
        /** half resolution, kept longer (the voice call keeps it for hanging up) */
        val half: Bitmap?,
    )

    /**
     * @param width the display width in pixels; a frame of another size is refused
     * @param height the display height in pixels
     * @param withHalf also make a half-resolution copy
     * @return null without root, on a timeout, an unknown pixel format, or a black frame
     * (a window that forbids screenshots, like a banking app): the effect then runs without it
     */
    fun capture(width: Int, height: Int, withHalf: Boolean, timeoutMs: Long = 800): Shot? {
        val started = SystemClock.uptimeMillis()
        val p = try {
            ProcessBuilder("su", "-c", "screencap").start()
        } catch (e: Exception) {
            Log.w(TAG, "no su", e)
            return null
        }
        // a hung su (a pending KernelSU prompt) must not keep the effect waiting
        val killer = Thread {
            try {
                Thread.sleep(timeoutMs)
                p.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        var soft: Bitmap? = null
        try {
            val input = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 16))
            // width, height, pixel format, data space (Android 9+), all little-endian ints
            val header = ByteArray(16)
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val w = h.getInt(0)
            val ht = h.getInt(4)
            val format = h.getInt(8)
            val sizeOk = w == width && ht == height || w == height && ht == width
            if (!sizeOk || format != RGBA_8888 && format != RGBX_8888) {
                Log.w(TAG, "unexpected frame ${w}x$ht format $format (display ${width}x$height)")
                return null
            }
            val data = ByteArray(w * ht * 4)
            input.readFully(data)
            if (looksBlack(data, w, ht)) {
                Log.i(TAG, "black frame (secure window?)")
                return null
            }
            val bitmap = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
            soft = bitmap
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
            // RGBX leaves the alpha byte undefined: it's a screen, everything is opaque
            bitmap.setHasAlpha(false)
            val full = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return null
            val half = if (withHalf) {
                val scaled = Bitmap.createScaledBitmap(bitmap, w / 2, ht / 2, true)
                scaled.copy(Bitmap.Config.HARDWARE, false).also { if (scaled !== bitmap) scaled.recycle() }
            } else null
            Log.i(TAG, "captured ${w}x$ht in ${SystemClock.uptimeMillis() - started} ms")
            return Shot(full, half)
        } catch (e: Exception) {
            Log.w(TAG, "capture failed after ${SystemClock.uptimeMillis() - started} ms", e)
            return null
        } finally {
            soft?.recycle()
            killer.interrupt()
            p.destroy()
        }
    }

    /** an 8 x 8 grid of samples, all (nearly) black */
    private fun looksBlack(data: ByteArray, w: Int, h: Int): Boolean {
        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                val x = (w * (2 * gx + 1)) / 16
                val y = (h * (2 * gy + 1)) / 16
                val i = (y * w + x) * 4
                val r = data[i].toInt() and 0xFF
                val g = data[i + 1].toInt() and 0xFF
                val b = data[i + 2].toInt() and 0xFF
                if (r > 6 || g > 6 || b > 6) return false
            }
        }
        return true
    }
}
