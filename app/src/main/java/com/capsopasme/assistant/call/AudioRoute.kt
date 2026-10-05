package com.capsopasme.assistant.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Where the call's audio goes, and which microphone hears the user.
 *
 * - [Kind.Media] (default): the answer plays like media, on the loudspeaker or on connected
 *   headphones at full quality; the phone's microphone (or a wired headset's) listens.
 * - [Kind.Earpiece]: held to the ear like a phone call (communication mode, earpiece).
 * - [Kind.Headset]: a Bluetooth headset's own microphone, at call quality (the "use the headset
 *   microphone" setting).
 *
 * The communication modes set the audio mode and the communication device; the TTS engine's
 * voice-communication stream and the voice-communication microphone follow them. Everything is
 * reset in [release], and by the system if this process dies.
 */
class AudioRoute(context: Context, private val onChanged: (kindChanged: Boolean) -> Unit) {

    enum class Kind { Media, Earpiece, Headset }

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var kind = Kind.Media
        private set

    /** the user switched the loudspeaker off (earpiece) */
    var earpieceWanted = false
        private set

    /** media audio goes to headphones: the loudspeaker / earpiece switch doesn't apply */
    var headphones = false
        private set

    var earpieceAvailable = false
        private set

    private var headsetMic = false
    private var communication = false
    private var started = false

    /** for the TTS engine and the tones */
    val outputAttributes: AudioAttributes
        get() = if (kind == Kind.Media) Speaker.defaultAttributes()
        else AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    val micSource: Int
        get() = if (kind == Kind.Media) MediaRecorder.AudioSource.VOICE_RECOGNITION
        else MediaRecorder.AudioSource.VOICE_COMMUNICATION

    /** what the volume keys should adjust on the call screen */
    val volumeStream: Int
        get() = if (kind == Kind.Media) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL

    fun start(useHeadsetMic: Boolean) {
        headsetMic = useHeadsetMic
        started = true
        am.registerAudioDeviceCallback(devices, main)
        apply()
    }

    fun setEarpiece(on: Boolean) {
        earpieceWanted = on
        apply()
    }

    private fun mediaOnHeadphones(): Boolean {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val out = try {
            am.getAudioDevicesForAttributes(attrs)
        } catch (_: Exception) {
            emptyList()
        }
        return out.any { it.type in HEADPHONE_TYPES }
    }

    fun release() {
        if (!started) return
        started = false
        main.removeCallbacks(reapply)
        am.unregisterAudioDeviceCallback(devices)
        if (communication) {
            try {
                am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {
                Log.w(TAG, "reset failed", e)
            }
            communication = false
        }
    }

    private fun apply() {
        if (!started) return
        val oldHeadphones = headphones
        val oldEarpiece = earpieceAvailable
        headphones = mediaOnHeadphones()
        val builtinEarpiece = commDevice(setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        earpieceAvailable = builtinEarpiece != null
        val headset = if (headsetMic) commDevice(HEADSET_TYPES) else null
        val earpiece = if (headset == null && earpieceWanted && !headphones) builtinEarpiece else null
        val (target, device) = when {
            headset != null -> Kind.Headset to headset
            earpiece != null -> Kind.Earpiece to earpiece
            else -> Kind.Media to null
        }
        try {
            if (device != null) {
                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (am.communicationDevice?.id != device.id && !am.setCommunicationDevice(device)) {
                    Log.w(TAG, "setCommunicationDevice(${device.type}) refused")
                }
                communication = true
            } else if (communication) {
                am.clearCommunicationDevice()
                am.mode = AudioManager.MODE_NORMAL
                communication = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "route change failed", e)
        }
        val kindChanged = target != kind
        kind = target
        if (kindChanged || oldHeadphones != headphones || oldEarpiece != earpieceAvailable) onChanged(kindChanged)
    }

    private fun commDevice(types: Set<Int>): AudioDeviceInfo? = try {
        am.availableCommunicationDevices.firstOrNull { it.type in types }
    } catch (_: Exception) {
        null
    }

    private val reapply = Runnable { apply() }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()

        private fun changed() {
            apply()
            // Bluetooth profiles come up one after another: look again once they settled
            main.removeCallbacks(reapply)
            main.postDelayed(reapply, 1_200)
        }
    }

    companion object {
        private const val TAG = "AudioRoute"
        private val HEADSET_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
