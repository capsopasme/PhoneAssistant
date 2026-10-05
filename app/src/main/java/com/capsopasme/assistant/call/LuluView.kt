package com.capsopasme.assistant.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.capsopasme.assistant.R
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 噜噜, the voice call's chubby little pig, drawn in code (an original design, no image assets).
 * Blinks now and then; puffs up and wiggles its ears with the user's voice while listening; looks
 * around with dots circling while thinking; talks with its mouth and sends out ripples while
 * speaking; sleeps, grey, when the microphone is off.
 *
 * Animates only while it is actually visible (not with the screen off or the call in the
 * background), with no allocation per frame.
 */
class LuluView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { Idle, Listening, Thinking, Speaking, Muted }

    var mode = Mode.Idle
        set(value) {
            if (field == value) return
            field = value
            modeSince = SystemClock.uptimeMillis()
            applyPalette()
            updateRunning()
            invalidate()
        }

    private var modeSince = SystemClock.uptimeMillis()
    private var level = 0f
    private var smoothLevel = 0f
    private var visibleToUser = false

    // blinking
    private val random = Random()
    private var blinkAt = SystemClock.uptimeMillis() + 1_500L
    private var blinkStart = -BLINK_MS

    // ---------------------------------------------------------------------------------------------
    // palette: awake, and the same desaturated for sleeping

    private val awake = intArrayOf(
        color(R.color.lulu_face_light), color(R.color.lulu_face), color(R.color.lulu_face_edge),
        color(R.color.lulu_ear), color(R.color.lulu_ear_inner), color(R.color.lulu_cheek),
        color(R.color.lulu_eye), color(R.color.lulu_snout), color(R.color.lulu_nostril),
        color(R.color.lulu_mouth), color(R.color.lulu_tongue),
    )
    private val asleep = IntArray(awake.size) { grey(awake[it]) }
    private val glow = color(R.color.lulu_glow)

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ear = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val snout = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nostril = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tongue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private var faceGradient: Shader? = null

    // ---------------------------------------------------------------------------------------------
    // geometry, in pixels, set in onSizeChanged

    /** head radius */
    private var r = 0f
    private var cx = 0f
    private var cy = 0f
    private val leftEar = Path()
    private val rightEar = Path()
    private val leftEarInner = Path()
    private val rightEarInner = Path()
    private var earPivotX = 0f
    private var earPivotY = 0f
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        applyPalette()
    }

    /** microphone level of the last chunk (RMS, 0..~0.3) */
    fun setLevel(rms: Float) {
        level = (rms * 14f).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        r = min(w, h) / 2f * 0.5f
        cx = w / 2f
        // a little low: the ears stick out above
        cy = h / 2f + 0.08f * r
        faceGradient = RadialGradient(
            cx - 0.3f * r, cy - 0.38f * r, (1.6f * r).coerceAtLeast(1f),
            intArrayOf(awake[FACE_LIGHT], awake[FACE], awake[FACE_EDGE]), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        buildEars()
        line.strokeWidth = 0.035f * r
        ring.strokeWidth = 0.035f * r
        applyPalette()
    }

    /** soft, rounded and tipped outwards, the inner part a smaller copy; the right one mirrored */
    private fun buildEars() {
        leftEar.reset()
        leftEar.moveTo(cx - 1.02f * r, cy - 0.38f * r)
        leftEar.quadTo(cx - 1.30f * r, cy - 0.78f * r, cx - 1.18f * r, cy - 1.02f * r)
        leftEar.quadTo(cx - 1.08f * r, cy - 1.18f * r, cx - 0.88f * r, cy - 1.08f * r)
        leftEar.quadTo(cx - 0.62f * r, cy - 0.96f * r, cx - 0.42f * r, cy - 0.80f * r)
        leftEar.close()
        val m = Matrix()
        leftEarInner.set(leftEar)
        m.setScale(0.55f, 0.55f, cx - 0.92f * r, cy - 0.86f * r)
        leftEarInner.transform(m)
        leftEarInner.offset(0.04f * r, 0.06f * r)
        m.setScale(-1f, 1f, cx, 0f)
        rightEar.set(leftEar)
        rightEar.transform(m)
        rightEarInner.set(leftEarInner)
        rightEarInner.transform(m)
        // where an ear meets the head
        earPivotX = 0.72f * r
        earPivotY = cy - 0.59f * r
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

    /** asleep (microphone off) needs no frames at all */
    private fun updateRunning() {
        val run = visibleToUser && mode != Mode.Muted
        if (run && !animator.isStarted) animator.start()
        else if (!run && animator.isStarted) animator.cancel()
    }

    private fun applyPalette() {
        val p = if (mode == Mode.Muted) asleep else awake
        if (mode == Mode.Muted) {
            face.shader = null
            face.color = p[FACE]
        } else {
            face.shader = faceGradient
        }
        ear.color = p[EAR]
        earInner.color = p[EAR_INNER]
        cheek.color = p[CHEEK]
        eye.color = p[EYE]
        line.color = p[EYE]
        snout.color = p[SNOUT]
        nostril.color = p[NOSTRIL]
        mouth.color = p[MOUTH]
        tongue.color = p[TONGUE]
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0f) return
        val now = SystemClock.uptimeMillis()
        val t = (now - modeSince) / 1000f
        val tau = 2f * PI.toFloat()
        smoothLevel += (level - smoothLevel) * 0.25f
        if (mode != Mode.Listening) level = 0f

        var scale = 1f
        var bob = 0f
        var earAngle = 0f
        var lookX = 0f
        var lookY = 0f
        var mouthOpen = 0f
        when (mode) {
            Mode.Listening -> {
                // puffs up with the voice, ears twitch
                scale = 1f + 0.12f * smoothLevel
                earAngle = 9f * smoothLevel * sin(tau * 3f * t)
                halo.color = withAlpha(glow, (40 + 60 * smoothLevel).toInt())
                canvas.drawCircle(cx, cy, r * scale * (1.18f + 0.3f * smoothLevel), halo)
            }
            Mode.Thinking -> {
                scale = 0.985f + 0.015f * sin(tau * 0.8f * t)
                bob = 0.02f * r * sin(tau * 0.8f * t)
                lookX = 0.045f * r * cos(tau * 0.45f * t)
                lookY = -0.035f * r + 0.012f * r * sin(tau * 0.9f * t)
                for (i in 0 until 3) {
                    val a = tau * (0.55f * t + i / 3f)
                    val pulse = 0.75f + 0.25f * sin(tau * (t + i / 3f))
                    dot.color = withAlpha(glow, (150 + 80 * pulse).toInt())
                    canvas.drawCircle(cx + r * 1.45f * cos(a), cy + r * 1.45f * sin(a), r * 0.075f * pulse, dot)
                }
            }
            Mode.Speaking -> {
                scale = 1f + 0.03f * sin(tau * 2.2f * t)
                earAngle = 4f * sin(tau * 2.2f * t)
                // syllable-like: fast open/close, slowly varying size
                mouthOpen = 0.3f + 0.7f * abs(sin(tau * 2.7f * t)) * (0.65f + 0.35f * sin(tau * 1.3f * t))
                for (k in 0 until 3) {
                    val p = ((t / 1.6f) + k / 3f) % 1f
                    ring.color = withAlpha(glow, ((1f - p) * 120).toInt())
                    canvas.drawCircle(cx, cy, r * (1.05f + 0.62f * p), ring)
                }
            }
            Mode.Idle -> scale = 0.985f + 0.015f * sin(tau * 0.4f * t)
            Mode.Muted -> scale = 0.95f
        }

        canvas.save()
        canvas.translate(0f, bob)
        canvas.scale(scale, scale, cx, cy)

        // ears behind the head
        canvas.save()
        canvas.rotate(-earAngle, cx - earPivotX, earPivotY)
        canvas.drawPath(leftEar, ear)
        canvas.drawPath(leftEarInner, earInner)
        canvas.restore()
        canvas.save()
        canvas.rotate(earAngle, cx + earPivotX, earPivotY)
        canvas.drawPath(rightEar, ear)
        canvas.drawPath(rightEarInner, earInner)
        canvas.restore()

        // a round, chubby, wide head
        oval(cx, cy, 1.12f * r, 0.96f * r)
        canvas.drawOval(rect, face)

        // blush
        oval(cx - 0.66f * r, cy + 0.16f * r, 0.19f * r, 0.11f * r)
        canvas.drawOval(rect, cheek)
        oval(cx + 0.66f * r, cy + 0.16f * r, 0.19f * r, 0.11f * r)
        canvas.drawOval(rect, cheek)

        drawEyes(canvas, now, lookX, lookY)

        // snout
        oval(cx, cy + 0.18f * r, 0.31f * r, 0.21f * r)
        canvas.drawOval(rect, snout)
        oval(cx - 0.10f * r, cy + 0.18f * r, 0.045f * r, 0.075f * r)
        canvas.drawOval(rect, nostril)
        oval(cx + 0.10f * r, cy + 0.18f * r, 0.045f * r, 0.075f * r)
        canvas.drawOval(rect, nostril)

        drawMouth(canvas, mouthOpen)
        canvas.restore()
    }

    private fun drawEyes(canvas: Canvas, now: Long, lookX: Float, lookY: Float) {
        val ey = cy - 0.22f * r + lookY
        if (mode == Mode.Muted) {
            // asleep: two contented curves
            for (side in SIDES) {
                val ex = cx + side * 0.36f * r
                rect.set(ex - 0.09f * r, ey - 0.07f * r, ex + 0.09f * r, ey + 0.05f * r)
                canvas.drawArc(rect, 20f, 140f, false, line)
            }
            return
        }
        // a blink every few seconds (not while looking around in thought)
        var open = 1f
        if (mode != Mode.Thinking) {
            if (now >= blinkAt && now - blinkStart > BLINK_MS) {
                blinkStart = now
                blinkAt = now + 2_200L + random.nextInt(3_000)
            }
            val since = now - blinkStart
            if (since in 0..BLINK_MS) open = abs(since - BLINK_MS / 2f) / (BLINK_MS / 2f)
        }
        val ry = 0.105f * r * open.coerceAtLeast(0.12f)
        for (side in SIDES) {
            val ex = cx + side * 0.36f * r + lookX
            oval(ex, ey, 0.085f * r, ry)
            canvas.drawOval(rect, eye)
            if (open > 0.5f) canvas.drawCircle(ex - 0.028f * r, ey - 0.04f * r, 0.032f * r, shine)
        }
    }

    private fun drawMouth(canvas: Canvas, open: Float) {
        val my = cy + 0.53f * r
        if (open < 0.05f) {
            // a small smile
            rect.set(cx - 0.10f * r, my - 0.08f * r, cx + 0.10f * r, my + 0.04f * r)
            canvas.drawArc(rect, 20f, 140f, false, line)
            return
        }
        val h = 0.04f * r + 0.13f * r * open
        oval(cx, my, 0.11f * r, h / 2f)
        canvas.drawOval(rect, mouth)
        if (open > 0.45f) {
            rect.set(cx - 0.065f * r, my + h / 2f - 0.07f * r * open, cx + 0.065f * r, my + h / 2f - 0.006f * r)
            canvas.drawOval(rect, tongue)
        }
    }

    private fun oval(x: Float, y: Float, rx: Float, ry: Float) = rect.set(x - rx, y - ry, x + rx, y + ry)

    private fun color(id: Int) = context.getColor(id)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** the same colour, mostly drained (keeps its alpha) */
    private fun grey(c: Int): Int {
        val red = Color.red(c)
        val green = Color.green(c)
        val blue = Color.blue(c)
        val l = 0.299f * red + 0.587f * green + 0.114f * blue
        fun mix(v: Int) = (v + (l - v) * 0.85f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), mix(red), mix(green), mix(blue))
    }

    companion object {
        private const val BLINK_MS = 150L
        private val SIDES = intArrayOf(-1, 1)

        // palette indices
        private const val FACE_LIGHT = 0
        private const val FACE = 1
        private const val FACE_EDGE = 2
        private const val EAR = 3
        private const val EAR_INNER = 4
        private const val CHEEK = 5
        private const val EYE = 6
        private const val SNOUT = 7
        private const val NOSTRIL = 8
        private const val MOUTH = 9
        private const val TONGUE = 10
    }
}
