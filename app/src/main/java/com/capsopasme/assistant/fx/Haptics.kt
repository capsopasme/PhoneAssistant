package com.capsopasme.assistant.fx

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The time-stop effect's only sound is no sound: a heavy thud when time stops, a light tick when
 * it flows again. Uses haptic primitives where the motor supports them, otherwise an amplitude
 * waveform, otherwise the stock heavy click.
 */
object Haptics {

    private const val TAG = "Haptics"

    fun timeStop(context: Context) = vibrate(context, heavy = true)

    fun timeResume(context: Context) = vibrate(context, heavy = false)

    private fun vibrate(context: Context, heavy: Boolean) {
        val v: Vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (heavy) heavyEffect(v) else lightEffect(v)
        try {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    private fun heavyEffect(v: Vibrator): VibrationEffect = when {
        v.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_THUD) ->
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 20)
                .compose()
        v.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_QUICK_FALL) ->
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 1f, 15)
                .compose()
        v.hasAmplitudeControl() ->
            VibrationEffect.createWaveform(longArrayOf(0, 24, 30, 70), intArrayOf(0, 255, 0, 140), -1)
        else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    private fun lightEffect(v: Vibrator): VibrationEffect = when {
        v.areAllPrimitivesSupported(Composition.PRIMITIVE_QUICK_RISE) ->
            VibrationEffect.startComposition()
                .addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 0.6f)
                .compose()
        v.hasAmplitudeControl() ->
            VibrationEffect.createWaveform(longArrayOf(0, 18), intArrayOf(0, 120), -1)
        else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    }
}
