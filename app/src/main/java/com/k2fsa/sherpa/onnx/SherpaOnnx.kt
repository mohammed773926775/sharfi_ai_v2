package com.k2fsa.sherpa.onnx

import android.util.Log
import java.io.File

/**
 * Sherpa-ONNX Kotlin/JNI Wrapper for Offline VITS/Piper Text-to-Speech.
 * Matches the official com.k2fsa.sherpa.onnx package and API signature.
 * Safely attempts to load native libsherpa-onnx-jni.so if bundled; if unavailable
 * or if the ONNX file is a lightweight placeholder, throws or signals cleanly so
 * OfflineTtsEngine can trigger its mandated native TextToSpeech fallback without crashing.
 */
data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var numThreads: Int = 2,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

class OfflineTts(
    val config: OfflineTtsConfig
) {
    private var ptr: Long = 0L
    private var isNativeReady: Boolean = false

    init {
        val modelFile = File(config.model.vits.model)
        val tokensFile = File(config.model.vits.tokens)
        require(modelFile.exists() && tokensFile.exists()) {
            "Sherpa-ONNX VITS model or tokens file missing at ${modelFile.absolutePath}"
        }
        // A real VITS Piper ONNX model is > 1 MB
        if (nativeLibraryLoaded && modelFile.length() > 1_000_000L) {
            try {
                ptr = newFromFile(config)
                isNativeReady = ptr != 0L
            } catch (t: Throwable) {
                Log.w(TAG, "Sherpa-ONNX native init failed, delegating to fallback: ${t.message}")
                isNativeReady = false
            }
        } else {
            Log.i(
                TAG,
                "Sherpa-ONNX JNI binary or full ONNX graph not active (size=${modelFile.length()}B, jni=$nativeLibraryLoaded)"
            )
            isNativeReady = false
        }
    }

    fun sampleRate(): Int {
        return if (isNativeReady && ptr != 0L) {
            try {
                getSampleRate(ptr)
            } catch (_: Throwable) {
                22050
            }
        } else {
            22050
        }
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        if (!isNativeReady || ptr == 0L) {
            throw IllegalStateException("Sherpa-ONNX native runtime unavailable; fallback to Android TextToSpeech required.")
        }
        val raw = generateImpl(ptr, text = text, sid = sid, speed = speed)
        @Suppress("UNCHECKED_CAST")
        val samples = raw[0] as? FloatArray ?: FloatArray(0)
        val sr = raw[1] as? Int ?: 22050
        return GeneratedAudio(samples = samples, sampleRate = sr)
    }

    fun release() {
        if (isNativeReady && ptr != 0L) {
            try {
                delete(ptr)
            } catch (_: Throwable) {
            }
            ptr = 0L
            isNativeReady = false
        }
    }

    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun generateImpl(ptr: Long, text: String, sid: Int, speed: Float): Array<Any>

    companion object {
        private const val TAG = "SherpaOnnx"
        var nativeLibraryLoaded: Boolean = false
            private set

        init {
            nativeLibraryLoaded = try {
                System.loadLibrary("sherpa-onnx-jni")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Throwable) {
                false
            }
        }
    }
}
