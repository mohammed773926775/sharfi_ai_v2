package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

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
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long = 0L

    init {
        val loaded = ensureNativeLoaded()
        if (!loaded) {
            throw UnsatisfiedLinkError("libsherpa-onnx-jni.so is not available on this device/build.")
        }
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        if (ptr == 0L) {
            throw IllegalStateException("Failed to initialize native Sherpa-ONNX OfflineTts pointer.")
        }
    }

    fun sampleRate(): Int = getSampleRate(ptr)

    fun numSpeakers(): Int = getNumSpeakers(ptr)

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        val raw = generateImpl(ptr, text, sid, speed)
            ?: throw IllegalStateException("Sherpa-ONNX generateImpl returned null.")
        @Suppress("UNCHECKED_CAST")
        val samples = raw[0] as FloatArray
        val sr = raw[1] as Int
        return GeneratedAudio(samples = samples, sampleRate = sr)
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineTtsConfig): Long
    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int
    private external fun generateImpl(ptr: Long, text: String, sid: Int, speed: Float): Array<Any>?

    companion object {
        @Volatile
        private var nativeLoaded = false

        fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return true
            return try {
                System.loadLibrary("sherpa-onnx-jni")
                nativeLoaded = true
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}
