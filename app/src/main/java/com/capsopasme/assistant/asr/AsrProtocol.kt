package com.capsopasme.assistant.asr

/**
 * Messenger protocol between the main process ([AsrClient]) and the isolated
 * recognizer process ([AsrService], `:asr`). Adapted from the fcitx5-android voice input.
 *
 * `Message.arg1` always carries the session id, so stale events can be dropped.
 */
object AsrProtocol {
    const val SAMPLE_RATE = 16000

    // client -> service
    const val MSG_START = 1
    const val MSG_AUDIO = 2
    const val MSG_STOP = 3
    const val MSG_CANCEL = 4
    const val MSG_SELF_TEST = 5

    // service -> client
    const val EVT_LOADING = 101
    const val EVT_READY = 102
    const val EVT_PARTIAL = 103
    const val EVT_FINAL = 104
    const val EVT_ERROR = 105
    const val EVT_DONE = 106
    const val EVT_TEST_RESULT = 107
    const val EVT_SPEECH_START = 108

    // bundle keys
    const val KEY_MODEL = "model"
    const val KEY_PARTIAL = "partial"
    const val KEY_SILENCE_MS = "silence_ms"
    const val KEY_KEEP_LOADED = "keep_loaded"
    const val KEY_PCM = "pcm"
    const val KEY_TEXT = "text"
    const val KEY_MESSAGE = "message"
    const val KEY_BACKEND = "backend"
    const val KEY_LOAD_MS = "load_ms"
    const val KEY_DECODE_MS = "decode_ms"
    const val KEY_AUDIO_MS = "audio_ms"
}
