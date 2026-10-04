/*
 * Adapted from the fcitx5-android voice input (VoiceEngine.kt)
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package com.capsopasme.assistant.asr

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.capsopasme.assistant.R
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Wraps a sherpa-onnx [OfflineRecognizer]. Only used inside the `:asr` process,
 * because native failures in QNN / onnxruntime call exit().
 */
class AsrEngine private constructor(
    val model: SpeechModel,
    private val recognizer: OfflineRecognizer,
    val loadMillis: Long,
) {
    val backendName: String
        get() = if (model.isQnn) "QNN HTP (NPU)" else "CPU"

    /** @return recognized text, trimmed */
    fun recognize(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrProtocol.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    class EngineException(message: String) : Exception(message)

    companion object {
        private const val TAG = "AsrEngine"
        const val VAD_WINDOW = 512

        @Volatile
        private var adspPathSet = false

        fun create(ctx: Context, model: SpeechModel): AsrEngine {
            val dir = ModelManager.modelDir(ctx, model)
            val missing = model.requiredFiles.filterNot { File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                throw EngineException("语音模型未安装（缺少 ${missing.joinToString()}），请在设置里下载")
            }
            val modelConfig = when (model) {
                SpeechModel.SenseVoiceQnnSM8650 -> {
                    checkQnnUsable(ctx)
                    OfflineModelConfig(
                        provider = "qnn",
                        senseVoice = OfflineSenseVoiceModelConfig(
                            language = "auto",
                            useInverseTextNormalization = true,
                            qnnConfig = QnnConfig(
                                backendLib = "libQnnHtp.so",
                                systemLib = "libQnnSystem.so",
                                contextBinary = File(dir, "model.bin").absolutePath,
                            ),
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        // only used for feature extraction
                        numThreads = 2,
                    )
                }
                SpeechModel.SenseVoiceCpu -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 4,
                )
            }
            val config = OfflineRecognizerConfig(modelConfig = modelConfig)
            val start = SystemClock.elapsedRealtime()
            val recognizer = try {
                OfflineRecognizer(assetManager = null, config = config)
            } catch (e: IllegalArgumentException) {
                throw EngineException("语音模型初始化失败：${e.message ?: ""}")
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "Loaded $model in ${elapsed}ms")
            return AsrEngine(model, recognizer, elapsed)
        }

        private fun checkQnnUsable(ctx: Context) {
            val soc = Build.SOC_MODEL
            if (soc.isNotBlank() && soc != Build.UNKNOWN && !soc.uppercase().startsWith("SM8650")) {
                throw EngineException("NPU 模型只支持 SM8650，本机是 $soc，请在设置里改用 CPU 模型")
            }
            val libDir = ctx.applicationInfo.nativeLibraryDir
            val required = listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so")
            val missing = required.filterNot { File(libDir, it).exists() }
            if (missing.isNotEmpty()) {
                throw EngineException("安装包里缺少 QNN 运行库：${missing.joinToString()}")
            }
            if (!adspPathSet) {
                // without ADSP_LIBRARY_PATH the DSP can't find libQnnHtpV75Skel.so (error 1008)
                OfflineRecognizer.prependAdspLibraryPath(libDir)
                adspPathSet = true
            }
        }

        /**
         * Silero VAD bundled as a raw resource, copied to internal storage atomically
         * (a truncated model would make onnxruntime abort() on every start).
         */
        fun createVad(ctx: Context, model: SpeechModel, minSilenceMs: Int): Vad {
            val file = File(ModelManager.rootDir(ctx), ModelManager.VAD_FILE_NAME)
            val bundled = ctx.resources.openRawResource(R.raw.silero_vad).use { it.readBytes() }
            if (!file.isFile || file.length() != bundled.size.toLong() || !file.readBytes().contentEquals(bundled)) {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(bundled)
                    out.fd.sync()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw EngineException("VAD 模型安装失败")
                }
            }
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = file.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = minSilenceMs / 1000f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    // soft limit, the service force-cuts at maxSegmentSeconds
                    maxSpeechDuration = (model.maxSegmentSeconds - 4f).coerceAtLeast(5f),
                ),
                sampleRate = AsrProtocol.SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            return Vad(assetManager = null, config = config)
        }
    }
}
