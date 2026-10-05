package com.capsopasme.assistant.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.capsopasme.assistant.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The call screen's big circle: swells with the user's voice while listening, orbiting dots
 * while thinking, ripples while speaking, grey when the microphone is off.
 *
 * Animates only while it is actually visible (not with the screen off or the call in the
 * background), with no allocation per frame.
 */
class OrbView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { Idle, Listening, Thinking, Speaking, Muted }

    var mode = Mode.Idle
        set(value) {
            if (field == value) return
            field = value
            modeSince = SystemClock.uptimeMillis()
            updateRunning()
            invalidate()
        }

    private var modeSince = SystemClock.uptimeMillis()
    private var level = 0f
    private var smoothLevel = 0f
    private var visibleToUser = false

    private val inner = context.getColor(R.color.orb_inner)
    private val outer = context.getColor(R.color.orb_outer)
    private val idle = context.getColor(R.color.orb_idle)

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: Shader? = null
    private var baseRadius = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    /** microphone level of the last chunk (RMS, 0..~0.3) */
    fun setLevel(rms: Float) {
        level = (rms * 14f).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseRadius = min(w, h) / 2f * 0.5f
        // a fixed gradient; the disc's radius changes, the colour beyond baseRadius clamps
        gradient = RadialGradient(
            w / 2f, h / 2f, baseRadius.coerceAtLeast(1f),
            intArrayOf(inner, outer), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        ring.strokeWidth = baseRadius * 0.035f
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateRunning() {
        val run = visibleToUser && mode != Mode.Muted
        if (run && !animator.isStarted) animator.start()
        else if (!run && animator.isStarted) animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val r0 = baseRadius
        if (r0 <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val t = (SystemClock.uptimeMillis() - modeSince) / 1000f
        smoothLevel += (level - smoothLevel) * 0.25f
        if (mode != Mode.Listening) level = 0f

        val r: Float
        when (mode) {
            Mode.Listening -> {
                r = r0 * (1f + 0.18f * smoothLevel)
                halo.color = withAlpha(outer, (40 + 50 * smoothLevel).toInt())
                canvas.drawCircle(cx, cy, r * (1.18f + 0.3f * smoothLevel), halo)
            }
            Mode.Thinking -> {
                r = r0 * (0.97f + 0.035f * sin(2f * PI.toFloat() * 0.8f * t))
                for (i in 0 until 3) {
                    val a = 2.0 * PI * (0.55 * t + i / 3.0)
                    val pulse = 0.75f + 0.25f * sin(2f * PI.toFloat() * (t + i / 3f))
                    dot.color = withAlpha(outer, (150 + 80 * pulse).toInt())
                    canvas.drawCircle(
                        cx + (r0 * 1.32f * cos(a)).toFloat(), cy + (r0 * 1.32f * sin(a)).toFloat(),
                        r0 * 0.075f * pulse, dot,
                    )
                }
            }
            Mode.Speaking -> {
                r = r0 * (1f + 0.05f * sin(2f * PI.toFloat() * 2.2f * t) + 0.03f * sin(2f * PI.toFloat() * 3.6f * t + 1.3f))
                for (k in 0 until 3) {
                    val p = ((t / 1.6f) + k / 3f) % 1f
                    ring.color = withAlpha(outer, ((1f - p) * 110).toInt())
                    canvas.drawCircle(cx, cy, r0 * (1.02f + 0.65f * p), ring)
                }
            }
            Mode.Idle -> r = r0 * (0.96f + 0.03f * sin(2f * PI.toFloat() * 0.5f * t))
            Mode.Muted -> r = r0 * 0.92f
        }
        if (mode == Mode.Muted) {
            disc.shader = null
            disc.color = idle
        } else {
            disc.shader = gradient
        }
        canvas.drawCircle(cx, cy, r, disc)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
