package com.capsopasme.assistant.fx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The time-stop effect: a frozen frame of the screen ([setWorld]) drawn through one AGSL shader.
 * A sphere bursts out from a point; inside it the colours turn negative, its rim bends and splits
 * the light like a lens; once it has swept the screen the world stays drained of colour, cool and
 * still. Leaving reverses it: the sphere collapses and the colours come back.
 *
 * - [Style.Freeze] (the assistant sheet, drawn below the card): the world freezes and stays
 *   frozen while the sheet is open; on exit, colour flows back in from the edges.
 * - [Style.Reveal] (the voice call, drawn over the call screen): the world freezes and the call
 *   bursts out of it (the sphere's inside is see-through); on hang-up the call collapses back into
 *   the sphere's centre and the world thaws.
 *
 * Without a frame (no root, a window that forbids screenshots) it draws a flat tinted version.
 * Per animation frame only a few shader uniforms change; the GPU does the rest. Nothing runs
 * between animations: the final frame stays as recorded. Kept INVISIBLE (laid out) when unused.
 */
class TimeStopView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Style { Freeze, Reveal }

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint().apply { shader = this@TimeStopView.shader }
    private var world: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var style = Style.Freeze
    private var entering = true
    private var maxRadius = 0f

    /** an animation is running */
    val isPlaying: Boolean get() = animator != null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWorld(null)
    }

    /**
     * The frozen frame, or null for the flat version. Not recycled here: the owner keeps it and
     * releases it once the view no longer draws it.
     */
    fun setWorld(bitmap: Bitmap?) {
        world = bitmap
        if (bitmap == null) {
            // a child must always be set; this one is never looked at (hasWorld = 0)
            shader.setInputShader("world", LinearGradient(0f, 0f, 1f, 0f, Color.BLACK, Color.BLACK, Shader.TileMode.CLAMP))
        } else {
            shader.setInputShader("world", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        shader.setFloatUniform("hasWorld", if (bitmap != null) 1f else 0f)
    }

    /**
     * Plays one half of the effect from ([cx], [cy]) in this view's coordinates. A running one
     * is dropped without its callback. [onEnd] runs on the main thread when it finished.
     */
    fun play(style: Style, enter: Boolean, cx: Float, cy: Float, onEnd: () -> Unit) {
        animator?.let {
            animator = null
            it.cancel()
        }
        visibility = VISIBLE
        if (width == 0 || height == 0) {
            // not laid out yet (it's kept INVISIBLE, not GONE, so this is rare)
            post { play(style, enter, cx, cy, onEnd) }
            return
        }
        this.style = style
        entering = enter
        mapWorld()
        val w = width.toFloat()
        val h = height.toFloat()
        maxRadius = max(max(hypot(cx, cy), hypot(w - cx, cy)), max(hypot(cx, h - cy), hypot(w - cx, h - cy)))
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("edge", max(24f, 0.06f * min(w, h)))
        // the flat version: the sheet only tints what's behind it, the call needs to cover itself
        if (style == Style.Freeze) {
            shader.setFloatUniform("fbNormal", 0f)
            shader.setFloatUniform("fbGraded", 0.5f)
        } else {
            shader.setFloatUniform("fbNormal", 1f)
            shader.setFloatUniform("fbGraded", 1f)
        }
        frame(0f)
        invalidate()
        val a = ValueAnimator.ofFloat(0f, 1f)
        animator = a
        a.duration = when {
            style == Style.Freeze && enter -> FREEZE_ENTER_MS
            style == Style.Freeze -> FREEZE_EXIT_MS
            enter -> REVEAL_ENTER_MS
            else -> REVEAL_EXIT_MS
        }
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            frame(it.animatedValue as Float)
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // dropped by a newer play / cancel: not this one's end
                if (animator !== a) return
                animator = null
                onEnd()
            }
        })
        a.start()
    }

    /** stops any animation, hides the view; the frame given to [setWorld] is no longer drawn */
    fun cancel() {
        animator?.let {
            animator = null
            it.cancel()
        }
        visibility = INVISIBLE
        setWorld(null)
    }

    override fun onDetachedFromWindow() {
        animator?.let {
            animator = null
            it.cancel()
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // runtime shaders only draw on the GPU
        if (!canvas.isHardwareAccelerated) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /** where this view sits on the captured frame; a frame from another orientation is dropped */
    private fun mapWorld() {
        val bmp = world ?: return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screen = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        if (screen.width() <= 0 || screen.height() <= 0) {
            setWorld(null)
            return
        }
        val sx = bmp.width / screen.width().toFloat()
        val sy = bmp.height / screen.height().toFloat()
        if (abs(sx - sy) > 0.03f * sx) {
            setWorld(null)
            return
        }
        shader.setFloatUniform("offset", loc[0].toFloat(), loc[1].toFloat())
        shader.setFloatUniform("worldScale", sx, sy)
    }

    /** the effect at [t] (0..1) of the current half */
    private fun frame(t: Float) {
        val r = maxRadius * 1.04f
        val radius: Float
        var inInv = 0f
        var inFrz = 0f
        var inClear = 0f
        var outInv = 0f
        var outFrz = 0f
        val ring: Float
        var flash = 0f
        var zoom = 1f
        when {
            style == Style.Freeze && entering -> {
                // impact: a flash and a small punch-in, then the negative sphere sweeps the screen
                // and settles into still, drained colours
                flash = 0.5f * (seg(t, 0f, 0.05f) - seg(t, 0.05f, 0.28f))
                zoom = 1f + 0.035f * sin(PI.toFloat() * seg(t, 0f, 0.35f))
                radius = r * easeOut(seg(t, 0.03f, 0.55f))
                ring = 1f - seg(t, 0.45f, 0.62f)
                inInv = 1f - seg(t, 0.5f, 0.85f)
                inFrz = seg(t, 0.45f, 0.9f)
            }
            style == Style.Freeze -> {
                // time flows again: the frozen sphere collapses, colour comes in from the edges
                radius = r * (1f - easeIn(seg(t, 0f, 0.92f)))
                ring = 0.8f * (1f - seg(t, 0.85f, 1f))
                inFrz = 1f
            }
            entering -> {
                // the world turns negative at once, the call bursts out of the sphere
                flash = 0.45f * (seg(t, 0f, 0.04f) - seg(t, 0.04f, 0.22f))
                zoom = 1f + 0.03f * sin(PI.toFloat() * seg(t, 0f, 0.3f))
                outInv = seg(t, 0f, 0.05f) * (1f - seg(t, 0.35f, 0.7f))
                outFrz = seg(t, 0.35f, 0.7f)
                radius = r * easeOut(seg(t, 0.12f, 0.85f))
                ring = 1f - seg(t, 0.78f, 0.98f)
                inClear = 1f
            }
            else -> {
                // the call collapses into its centre, the world around it thaws
                radius = r * (1f - easeIn(seg(t, 0f, 0.82f)))
                ring = 1f - seg(t, 0.8f, 0.95f)
                inClear = 1f
                outFrz = 1f - seg(t, 0.25f, 0.95f)
            }
        }
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("zoom", zoom)
        shader.setFloatUniform("inInv", inInv)
        shader.setFloatUniform("inFrz", inFrz)
        shader.setFloatUniform("inClear", inClear)
        shader.setFloatUniform("outInv", outInv)
        shader.setFloatUniform("outFrz", outFrz)
        shader.setFloatUniform("outClear", 0f)
        shader.setFloatUniform("ring", ring)
        shader.setFloatUniform("flash", flash.coerceIn(0f, 1f))
    }

    private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
    private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
    private fun easeIn(x: Float) = x * x * x

    companion object {
        const val FREEZE_ENTER_MS = 950L
        const val FREEZE_EXIT_MS = 480L
        const val REVEAL_ENTER_MS = 950L
        const val REVEAL_EXIT_MS = 620L

        private val AGSL = """
            uniform shader world;
            uniform float hasWorld;
            uniform float2 size;
            uniform float2 offset;
            uniform float2 worldScale;
            uniform float2 center;
            uniform float radius;
            uniform float edge;
            uniform float zoom;
            uniform float inInv;
            uniform float inFrz;
            uniform float inClear;
            uniform float outInv;
            uniform float outFrz;
            uniform float outClear;
            uniform float ring;
            uniform float flash;
            uniform float fbNormal;
            uniform float fbGraded;

            float3 worldAt(float2 p) {
                return float3(world.eval((p + offset) * worldScale).rgb);
            }

            half4 main(float2 p) {
                float2 v = p - center;
                float d = length(v);
                float2 dir = d > 0.5 ? v / d : float2(0.0);
                // 1 on the sphere's rim, fading within an edge width
                float x = (d - radius) / edge;
                float band = exp(-x * x) * ring;
                float inside = 1.0 - smoothstep(radius - 2.0, radius + 2.0, d);

                float inv = mix(outInv, inInv, inside);
                float frz = mix(outFrz, inFrz, inside);
                float clear = mix(outClear, inClear, inside);

                // the rim bends the world outwards and splits red and blue apart
                float2 q = center + v / zoom - dir * band * edge * 0.3;
                float ca = band * 8.0;
                float3 seen = float3(worldAt(q - dir * ca).r, worldAt(q).g, worldAt(q + dir * ca).b);
                // no frame: a flat dark tint instead (see/through for the sheet, solid for the call)
                float3 rgb = mix(float3(0.07, 0.08, 0.13), seen, hasWorld);
                float alpha = mix(mix(fbNormal, fbGraded, max(inv, frz)), 1.0, hasWorld);
                // negative
                rgb = mix(rgb, 1.0 - rgb, inv);
                // stopped time: drained of colour, cool, darker towards the corners
                float l = dot(rgb, float3(0.299, 0.587, 0.114));
                float vignette = 1.0 - 0.35 * smoothstep(0.25, 0.75, length(p / size - 0.5));
                float3 still = mix(rgb, float3(l), 0.85) * float3(0.80, 0.87, 1.0) * 0.9 * vignette;
                rgb = mix(rgb, still, frz);

                float4 col = float4(rgb * alpha, alpha) * (1.0 - clear);
                // glowing rim, then the flash, both drawn over (premultiplied)
                float g = clamp(band * 0.6, 0.0, 1.0);
                col = col * (1.0 - g) + float4(float3(0.86, 0.91, 1.0) * g, g);
                col = col * (1.0 - flash) + float4(flash);
                return half4(col);
            }
        """.trimIndent()
    }
}
