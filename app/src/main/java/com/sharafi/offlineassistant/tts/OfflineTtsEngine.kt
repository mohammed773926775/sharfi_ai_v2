package com.sharafi.offlineassistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * MANDATE 2: Offline Text-to-Speech Engine powered by com.k2fsa.sherpa.onnx (VITS Piper Arabic & English)
 * with automatic first-launch model downloads from HuggingFace/Sherpa-ONNX CDN and resilient fallback
 * to Android's native TextToSpeech if Sherpa-ONNX fails.
 */
class OfflineTtsEngine(private val context: Context) {

    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sherpaArabicTts: OfflineTts? = null
    private var sherpaEnglishTts: OfflineTts? = null

    @Volatile
    private var nativeTtsReady: Boolean = false
    private var nativeTts: TextToSpeech? = null

    private val sherpaRootDir: File
        get() = File(context.filesDir, "models/sherpa_tts")

    private val arabicModelDir: File
        get() = File(sherpaRootDir, ARABIC_MODEL_FOLDER)

    private val englishModelDir: File
        get() = File(sherpaRootDir, ENGLISH_MODEL_FOLDER)

    init {
        initNativeFallbackTts()
    }

    private fun initNativeFallbackTts() {
        try {
            nativeTts = TextToSpeech(context.applicationContext) { status ->
                nativeTtsReady = (status == TextToSpeech.SUCCESS)
                if (nativeTtsReady) {
                    Log.i(TAG, "Native Android TextToSpeech fallback engine initialized.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize native TTS fallback: ${e.message}")
        }
    }

    fun areModelsDownloaded(): Boolean {
        return isValidPiperModelDir(arabicModelDir) && isValidPiperModelDir(englishModelDir)
    }

    private fun isValidPiperModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val hasOnnx = dir.listFiles()?.any { it.extension.equals("onnx", ignoreCase = true) } == true
        val hasTokens = File(dir, "tokens.txt").exists()
        val hasEspeak = File(dir, "espeak-ng-data").exists()
        return hasOnnx && hasTokens && hasEspeak
    }

    /**
     * Downloads and extracts both Arabic (vits-piper-ar) and English (vits-piper-en)
     * Sherpa-ONNX VITS Piper model bundles into internal storage.
     */
    suspend fun downloadModelsIfNeeded(
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            sherpaRootDir.mkdirs()

            if (!isValidPiperModelDir(arabicModelDir)) {
                downloadPiperModelBundle(
                    targetDir = arabicModelDir,
                    displayName = "Sherpa-ONNX Arabic TTS (vits-piper-ar)",
                    archiveUrls = listOf(
                        "https://huggingface.co/4-alokk/piper-voices/resolve/main/vits-piper-ar_JO-kareem-medium.tar.bz2",
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ar_JO-kareem-medium.tar.bz2"
                    ),
                    directFileBaseUrl = "https://huggingface.co/csukuangfj/vits-piper-ar_JO-kareem-medium/resolve/main",
                    onnxFileName = "ar_JO-kareem-medium.onnx"
                ) { fraction, msg ->
                    onProgress(fraction * 0.5f, msg)
                }
            }

            if (!isValidPiperModelDir(englishModelDir)) {
                downloadPiperModelBundle(
                    targetDir = englishModelDir,
                    displayName = "Sherpa-ONNX English TTS (vits-piper-en)",
                    archiveUrls = listOf(
                        "https://huggingface.co/4-alokk/piper-voices/resolve/main/vits-piper-en_US-amy-low.tar.bz2",
                        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2"
                    ),
                    directFileBaseUrl = "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main",
                    onnxFileName = "en_US-amy-low.onnx"
                ) { fraction, msg ->
                    onProgress(0.5f + (fraction * 0.5f), msg)
                }
            }

            onProgress(1.0f, "Sherpa-ONNX TTS models ready in internal storage.")
        }
    }

    private fun downloadPiperModelBundle(
        targetDir: File,
        displayName: String,
        archiveUrls: List<String>,
        directFileBaseUrl: String,
        onnxFileName: String,
        onStepProgress: (Float, String) -> Unit
    ) {
        val tempArchive = File(sherpaRootDir, "${targetDir.name}.tar.bz2.tmp")
        var archiveDownloaded = false
        var lastError: Exception? = null

        for (urlStr in archiveUrls) {
            try {
                onStepProgress(0.02f, "Connecting to HuggingFace for $displayName...")
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    connect()
                }

                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP ${conn.responseCode} from $urlStr")
                }

                val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
                var readBytes = 0L

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var count: Int
                        while (input.read(buf).also { count = it } != -1) {
                            output.write(buf, 0, count)
                            readBytes += count
                            val ratio = (readBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val mbRead = readBytes / (1024 * 1024)
                            val mbTotal = totalBytes / (1024 * 1024)
                            onStepProgress(
                                ratio * 0.80f,
                                "Downloading $displayName ($mbRead / $mbTotal MB)"
                            )
                        }
                    }
                }
                conn.disconnect()
                archiveDownloaded = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Archive URL failed ($urlStr): ${e.message}")
            }
        }

        if (!archiveDownloaded) {
            throw lastError ?: IllegalStateException("Failed to download $displayName from $directFileBaseUrl")
        }

        onStepProgress(0.85f, "Extracting $displayName to internal storage...")
        val stagingDir = File(sherpaRootDir, "${targetDir.name}_staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(tempArchive)))
        ).use { tarIn ->
            var entry = tarIn.nextTarEntry
            while (entry != null) {
                val outFile = File(stagingDir, entry.name)
                if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                    throw SecurityException("Tar entry outside target path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        tarIn.copyTo(fos, 8192)
                    }
                }
                entry = tarIn.nextTarEntry
            }
        }
        tempArchive.delete()

        val children = stagingDir.listFiles() ?: emptyArray()
        val extractedRoot = if (children.size == 1 && children[0].isDirectory) {
            children[0]
        } else {
            stagingDir
        }

        if (targetDir.exists()) targetDir.deleteRecursively()
        if (!extractedRoot.renameTo(targetDir)) {
            extractedRoot.copyRecursively(targetDir, overwrite = true)
        }
        stagingDir.deleteRecursively()

        // Ensure standard model filename alias if needed
        val onnxFiles = targetDir.listFiles()?.filter { it.extension == "onnx" }.orEmpty()
        if (onnxFiles.isEmpty()) {
            throw IllegalStateException("No .onnx file found after extracting $displayName ($onnxFileName)")
        }
        onStepProgress(1.0f, "$displayName installed.")
    }

    /**
     * Initializes both Arabic and English Sherpa-ONNX OfflineTts instances from internal storage.
     * If Sherpa-ONNX fails to load on the device, logs the error and keeps native TextToSpeech fallback active.
     */
    suspend fun initializeModels(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (sherpaArabicTts == null && isValidPiperModelDir(arabicModelDir)) {
                sherpaArabicTts = buildSherpaTtsInstance(arabicModelDir)
            }
            if (sherpaEnglishTts == null && isValidPiperModelDir(englishModelDir)) {
                sherpaEnglishTts = buildSherpaTtsInstance(englishModelDir)
            }
            Unit
        }.onFailure { err ->
            Log.w(TAG, "Sherpa-ONNX initialization warning (Native TTS fallback remains active): ${err.message}")
        }
    }

    private fun buildSherpaTtsInstance(modelDir: File): OfflineTts {
        val onnxFile = modelDir.listFiles()?.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
            ?: throw IllegalStateException("Missing .onnx model file in ${modelDir.absolutePath}")
        val tokensFile = File(modelDir, "tokens.txt")
        val dataDir = File(modelDir, "espeak-ng-data")

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = onnxFile.absolutePath,
            lexicon = "",
            tokens = tokensFile.absolutePath,
            dataDir = dataDir.absolutePath,
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineTtsConfig(
            model = modelConfig,
            maxNumSentences = 2
        )
        return OfflineTts(assetManager = null, config = config)
    }

    /**
     * Speaks the response using Sherpa-ONNX neural TTS first, and falls back to Android's native
     * TextToSpeech if Sherpa-ONNX is unavailable or throws an error.
     */
    fun speak(text: String, preferArabic: Boolean = containsArabicScript(text)) {
        if (text.isBlank()) return
        ttsScope.launch {
            val spokenBySherpa = trySpeakWithSherpa(text, preferArabic)
            if (!spokenBySherpa) {
                speakWithNativeFallback(text, preferArabic)
            }
        }
    }

    private fun trySpeakWithSherpa(text: String, preferArabic: Boolean): Boolean {
        return try {
            val engine = if (preferArabic) sherpaArabicTts else sherpaEnglishTts
            if (engine == null) return false

            val audio = engine.generate(text = text, sid = 0, speed = 1.0f)
            if (audio.samples.isEmpty()) return false

            playFloatPcmAudio(audio.samples, audio.sampleRate)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Sherpa-ONNX synthesis failed, falling back to native TextToSpeech: ${t.message}")
            false
        }
    }

    private fun playFloatPcmAudio(samples: FloatArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(samples.size * 4)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        audioTrack.play()
    }

    private fun speakWithNativeFallback(text: String, preferArabic: Boolean) {
        val tts = nativeTts
        if (!nativeTtsReady || tts == null) {
            Log.e(TAG, "Both Sherpa-ONNX and native TextToSpeech fallback are unavailable.")
            return
        }
        val locale = if (preferArabic) Locale("ar") else Locale.US
        tts.language = locale
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private fun containsArabicScript(text: String): Boolean {
        return text.any { ch -> ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' }
    }

    fun release() {
        try {
            sherpaArabicTts?.release()
            sherpaEnglishTts?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Sherpa-ONNX", e)
        }
        try {
            nativeTts?.stop()
            nativeTts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down native TTS", e)
        }
    }

    companion object {
        private const val TAG = "OfflineTtsEngine"
        const val ARABIC_MODEL_FOLDER = "vits-piper-ar"
        const val ENGLISH_MODEL_FOLDER = "vits-piper-en"
    }
}
