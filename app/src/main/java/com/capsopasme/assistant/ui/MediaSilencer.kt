package com.capsopasme.assistant.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.capsopasme.assistant.Prefs

/**
 * Keeps music / video from playing out of the phone's loudspeaker while the assistant sheet is
 * open, so it doesn't drown out (or get transcribed into) what the user says.
 *
 * Only acts when something is playing and media goes to the built-in speaker: headphones and
 * Bluetooth don't reach the microphone, so they are left alone.
 *
 * 1. Takes transient exclusive audio focus: well-behaved players (Gramophone, browsers, most
 *    video players) pause, and resume by themselves when the focus is given back.
 * 2. If something still plays shortly after (a player that ignores audio focus), the media
 *    stream is muted until [release]. The mute is remembered in the settings, so it is undone
 *    the next time the app runs even if this process got killed in between.
 */
class MediaSilencer(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())

    private var focus: AudioFocusRequest? = null
    private var muted = false

    /** Call when the sheet appears (again). Does nothing if nothing plays on the speaker. */
    fun engage() {
        if (focus != null) return
        restoreLeftover(am, prefs)
        if (!am.isMusicActive || !mediaOnSpeaker()) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // losing it to someone else (a call, a player we launched) needs no action here
            .setOnAudioFocusChangeListener({ }, main)
            .build()
        am.requestAudioFocus(request)
        focus = request
        main.postDelayed(muteIfStillPlaying, MUTE_CHECK_MS)
    }

    /** Call when the sheet goes away: unmute and hand the focus back (paused players resume). */
    fun release() {
        main.removeCallbacks(muteIfStillPlaying)
        if (muted) {
            muted = false
            unmute(am, prefs)
        }
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private val muteIfStillPlaying = Runnable {
        if (focus == null || muted) return@Runnable
        if (am.isMusicActive && !am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            prefs.musicMutedByAssistant = true
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            muted = true
        }
    }

    private fun mediaOnSpeaker(): Boolean {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val devices = try {
            am.getAudioDevicesForAttributes(attrs)
        } catch (_: Exception) {
            emptyList()
        }
        // unknown routing: assume the speaker, the common case
        if (devices.isEmpty()) return true
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
        }
    }

    companion object {
        /** players get this long to react to the focus loss before the stream is muted */
        private const val MUTE_CHECK_MS = 450L

        /** undo a mute left behind by a session that never got to [release] (process killed) */
        fun restoreLeftover(context: Context) {
            restoreLeftover(context.getSystemService(AudioManager::class.java), Prefs(context))
        }

        private fun restoreLeftover(am: AudioManager, prefs: Prefs) {
            if (prefs.musicMutedByAssistant) unmute(am, prefs)
        }

        private fun unmute(am: AudioManager, prefs: Prefs) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            prefs.musicMutedByAssistant = false
        }
    }
}
