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
 * The call screen's one round orb with a soft glow around it, the classic voice-call look:
 * it swells with the user's voice while listening, breathes slowly while thinking, pulses in a
 * speaking rhythm while the answer plays, and is a still grey disc when the microphone is off.
 * Size and glow ease towards each mode's values, so switching modes never jumps.
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
    private var discGradient: Shader? = null

    /** the orb's radius at rest */
    private var baseRadius = 0f

    /** what is drawn, easing towards the mode's target; 0 = not drawn yet */
    private var radius = 0f
    private var glow = 0f

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
        val cx = w / 2f
        val cy = h / 2f
        // lit a little from the upper left; beyond the rest radius the edge colour clamps
        discGradient = RadialGradient(
            cx - 0.25f * baseRadius, cy - 0.3f * baseRadius, (1.3f * baseRadius).coerceAtLeast(1f),
            intArrayOf(inner, outer), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        // fixed for the rest radius; scaled with the orb when drawn
        val clear = outer and 0x00FFFFFF
        halo.shader = RadialGradient(
            cx, cy, (HALO * baseRadius).coerceAtLeast(1f),
            intArrayOf(withAlpha(outer, 0x70), withAlpha(outer, 0x70), clear), floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        radius = 0f
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

    /** a still, grey orb (microphone off) needs no frames at all */
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

        val targetRadius: Float
        val targetGlow: Float
        when (mode) {
            Mode.Listening -> {
                targetRadius = r0 * (1f + 0.16f * smoothLevel)
                targetGlow = 0.35f + 0.65f * smoothLevel
            }
            Mode.Thinking -> {
                val breath = 0.5f - 0.5f * cos(TAU * 0.7f * t)
                targetRadius = r0 * (0.95f + 0.04f * breath)
                targetGlow = 0.15f + 0.3f * breath
            }
            Mode.Speaking -> {
                // syllable-like beats whose strength drifts slowly
                val beat = (0.5f - 0.5f * cos(TAU * 2.4f * t)) * (0.65f + 0.35f * sin(TAU * 0.9f * t))
                targetRadius = r0 * (1f + 0.07f * beat)
                targetGlow = 0.35f + 0.5f * beat
            }
            Mode.Idle -> {
                targetRadius = r0 * (0.97f + 0.02f * sin(TAU * 0.4f * t))
                targetGlow = 0.15f
            }
            Mode.Muted -> {
                targetRadius = r0 * 0.92f
                targetGlow = 0f
            }
        }
        if (radius <= 0f || mode == Mode.Muted) {
            // first frame, or no more frames coming: straight to the target
            radius = targetRadius
            glow = targetGlow
        } else {
            radius += (targetRadius - radius) * 0.3f
            glow += (targetGlow - glow) * 0.2f
        }

        if (glow > 0.01f) {
            halo.alpha = (glow * 255).toInt().coerceIn(0, 255)
            val s = radius / r0
            canvas.save()
            canvas.scale(s, s, cx, cy)
            canvas.drawCircle(cx, cy, HALO * r0, halo)
            canvas.restore()
        }
        if (mode == Mode.Muted) {
            disc.shader = null
            disc.color = idle
        } else {
            disc.shader = discGradient
        }
        canvas.drawCircle(cx, cy, radius, disc)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    companion object {
        private const val TAU = 2f * PI.toFloat()

        /** the glow's outer radius, in rest radii (it fits the view at the loudest voice) */
        private const val HALO = 1.55f
    }
}
