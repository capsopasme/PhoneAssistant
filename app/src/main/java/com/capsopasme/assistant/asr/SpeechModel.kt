package com.capsopasme.assistant.asr

/**
 * Offline speech recognition models (sherpa-onnx release assets).
 * Same archives as the fcitx5-android voice input, so a model can be copied over from there.
 */
enum class SpeechModel(
    val label: String,
    /** Release tag + archive name under https://github.com/k2-fsa/sherpa-onnx/releases/download/ */
    val releasePath: String,
    /** Top-level directory inside the archive */
    val dirName: String,
    /** Files (relative to [dirName]) that must exist for the model to be usable */
    val requiredFiles: List<String>,
    /** Rough download size, for UI only */
    val downloadSizeMb: Int,
    /** Run on Qualcomm HTP (NPU) via QNN */
    val isQnn: Boolean,
    /** Longest audio segment the model takes; QNN models have a fixed input length */
    val maxSegmentSeconds: Float,
    /** Test wav (relative to [dirName]) kept after extraction, used by the self test */
    val testWav: String,
) {
    /** SenseVoice-Small on Snapdragon 8 Gen 3 (SM8650, Hexagon v75) NPU, QNN 2.40, fixed 20s input */
    SenseVoiceQnnSM8650(
        "SenseVoice · 骁龙 8 Gen 3 NPU（推荐）",
        "asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",
        "sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8",
        listOf("model.bin", "tokens.txt"),
        downloadSizeMb = 157,
        isQnn = true,
        maxSegmentSeconds = 19f,
        testWav = "test_wavs/zh.wav",
    ),

    /** SenseVoice-Small int8 on CPU, fallback when the NPU path fails */
    SenseVoiceCpu(
        "SenseVoice · CPU（兜底）",
        "asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        listOf("model.int8.onnx", "tokens.txt"),
        downloadSizeMb = 156,
        isQnn = false,
        maxSegmentSeconds = 25f,
        testWav = "test_wavs/zh.wav",
    );

    val defaultUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$releasePath"

    /** keep model files and a single test wav, skip the rest */
    fun shouldExtract(pathInArchive: String): Boolean {
        val rel = pathInArchive.removePrefix("$dirName/")
        if (rel == pathInArchive) return false
        if (rel.startsWith("test_wavs/")) return rel == testWav
        return rel.endsWith(".onnx") || rel.endsWith(".bin") || rel.endsWith(".txt") ||
                rel == "LICENSE" || rel == "README.md"
    }

    companion object {
        fun fromDirName(name: String) = entries.firstOrNull { it.dirName == name }
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: SenseVoiceQnnSM8650
    }
}
