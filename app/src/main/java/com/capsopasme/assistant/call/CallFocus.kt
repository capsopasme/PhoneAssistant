package com.capsopasme.assistant.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Audio focus for the whole call: music and videos pause (whatever the output, the answers
 * would talk over them) and resume after the call. A phone call ringing or an alarm going off
 * takes the focus away: [onInterrupted] then ends the call.
 *
 * A player that ignores audio focus gets a pause key instead (muting the media stream as the
 * sheet does would mute the answers too, they play on the same stream), and a play key after.
 */
class CallFocus(context: Context, private val onInterrupted: () -> Unit) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var pausedByKey = false

    /** @return false if the focus is refused (a phone call is going on) */
    fun acquire(): Boolean {
        if (request != null) return true
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        if (request != null) onInterrupted()
                    // a navigation prompt or a notification sound: carry on
                    else -> {}
                }
            }, main)
            .build()
        if (am.requestAudioFocus(r) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = r
        val musicWasActive = am.isMusicActive
        if (musicWasActive) main.postDelayed(pauseIfStillPlaying, PAUSE_CHECK_MS)
        return true
    }

    /** the user paused or changed the music themselves (media_control): don't resume it */
    fun forgetPausedPlayer() {
        pausedByKey = false
    }

    /** @param resume resume a player paused with the pause key (not when another app opens) */
    fun release(resume: Boolean) {
        main.removeCallbacks(pauseIfStillPlaying)
        val r = request ?: return
        request = null
        am.abandonAudioFocusRequest(r)
        if (pausedByKey && resume) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        pausedByKey = false
    }

    private val pauseIfStillPlaying = Runnable {
        if (request != null && am.isMusicActive) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByKey = true
        }
    }

    private fun mediaKey(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    companion object {
        /** players get this long to react to the focus loss (before the first answer can play) */
        private const val PAUSE_CHECK_MS = 450L
    }
}
