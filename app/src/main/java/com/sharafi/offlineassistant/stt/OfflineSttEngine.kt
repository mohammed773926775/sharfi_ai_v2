package com.sharafi.offlineassistant.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * MANDATE 1: Offline Speech-to-Text Engine powered strictly by Vosk (com.alphacephei:vosk-android:0.3.47).
 * Never uses Android's native SpeechRecognizer.
 * Downloads and unpacks both Arabic (vosk-model-small-ar-0.3) and English (vosk-model-small-en-us-0.15)
 * models from https://alphacephei.com/vosk/models/ into internal storage on first launch.
 */
class OfflineSttEngine(
    private val context: Context,
    private val onFinalTranscript: (String, LanguageMode) -> Unit
) : RecognitionListener {

    enum class LanguageMode(val code: String, val modelFolderName: String) {
        ARABIC("ar", "vosk-model-small-ar-0.3"),
        ENGLISH("en", "vosk-model-small-en-us-0.15")
    }

    data class SttState(
        val isInitialized: Boolean = false,
        val isListening: Boolean = false,
        val activeLanguage: LanguageMode = LanguageMode.ARABIC,
        val partialTranscript: String = "",
        val finalTranscript: String = "",
        val errorMessage: String? = null
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(SttState())
    val state: StateFlow<SttState> = _state.asStateFlow()

    private var arabicModel: Model? = null
    private var englishModel: Model? = null
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null

    private val voskRootDir: File
        get() = File(context.filesDir, "models/vosk")

    fun areModelsDownloaded(): Boolean {
        val arDir = File(voskRootDir, LanguageMode.ARABIC.modelFolderName)
        val enDir = File(voskRootDir, LanguageMode.ENGLISH.modelFolderName)
        return isValidVoskModelDir(arDir) && isValidVoskModelDir(enDir)
    }

    private fun isValidVoskModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val confDir = File(dir, "conf")
        val amDir = File(dir, "am")
        return confDir.exists() || amDir.exists() || (dir.listFiles()?.isNotEmpty() == true)
    }

    /**
     * Downloads and unzips both Vosk Arabic and English models into internal storage,
     * reporting granular progress [0.0f..1.0f] and status messages.
     */
    suspend fun downloadModelsIfNeeded(
        onProgress: (Float, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            voskRootDir.mkdirs()

            val modelsToFetch = listOf(
                ModelDownloadSpec(
                    folderName = LanguageMode.ARABIC.modelFolderName,
                    displayName = "Vosk Arabic Model (vosk-model-small-ar-0.3)",
                    urls = listOf(
                        "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip",
                        "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
                    )
                ),
                ModelDownloadSpec(
                    folderName = LanguageMode.ENGLISH.modelFolderName,
                    displayName = "Vosk English Model (vosk-model-small-en-us-0.15)",
                    urls = listOf(
                        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
                    )
                )
            )

            modelsToFetch.forEachIndexed { index, spec ->
                val targetDir = File(voskRootDir, spec.folderName)
                if (!isValidVoskModelDir(targetDir)) {
                    val baseProgress = index.toFloat() / modelsToFetch.size
                    val slice = 1.0f / modelsToFetch.size
                    downloadAndExtractZip(
                        urls = spec.urls,
                        targetModelDir = targetDir,
                        label = spec.displayName
                    ) { modelFraction, statusText ->
                        onProgress(baseProgress + (modelFraction * slice), statusText)
                    }
                }
            }
            onProgress(1.0f, "Vosk STT models ready in internal storage.")
        }
    }

    private fun downloadAndExtractZip(
        urls: List<String>,
        targetModelDir: File,
        label: String,
        onStepProgress: (Float, String) -> Unit
    ) {
        val tempZip = File(voskRootDir, "${targetModelDir.name}.zip.tmp")
        var lastError: Exception? = null
        var downloaded = false

        for (urlString in urls) {
            try {
                onStepProgress(0.02f, "Connecting to $label...")
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    connect()
                }

                if (connection.responseCode !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("HTTP ${connection.responseCode} for $urlString")
                }

                val totalBytes = connection.contentLengthLong.coerceAtLeast(1L)
                var downloadedBytes = 0L

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val dlRatio = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val mbRead = downloadedBytes / (1024 * 1024)
                            val mbTotal = totalBytes / (1024 * 1024)
                            onStepProgress(
                                dlRatio * 0.80f,
                                "Downloading $label ($mbRead / $mbTotal MB)"
                            )
                        }
                    }
                }
                connection.disconnect()
                downloaded = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Primary URL failed ($urlString), trying fallback if available: ${e.message}")
            }
        }

        if (!downloaded) {
            throw lastError ?: IllegalStateException("Unable to download $label")
        }

        onStepProgress(0.85f, "Unzipping $label to internal storage...")
        val stagingDir = File(voskRootDir, "${targetModelDir.name}_staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(stagingDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos, 8192)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        tempZip.delete()

        // Normalize nested top-level folder inside ZIP archive
        val children = stagingDir.listFiles() ?: emptyArray()
        val extractedRoot = if (children.size == 1 && children[0].isDirectory) {
            children[0]
        } else {
            stagingDir
        }

        if (targetModelDir.exists()) targetModelDir.deleteRecursively()
        if (!extractedRoot.renameTo(targetModelDir)) {
            extractedRoot.copyRecursively(targetModelDir, overwrite = true)
        }
        stagingDir.deleteRecursively()
        onStepProgress(1.0f, "$label installed.")
    }

    /**
     * Loads both Vosk models from internal storage into memory.
     */
    suspend fun initializeModels(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val arPath = File(voskRootDir, LanguageMode.ARABIC.modelFolderName).absolutePath
            val enPath = File(voskRootDir, LanguageMode.ENGLISH.modelFolderName).absolutePath

            if (arabicModel == null) {
                arabicModel = Model(arPath)
            }
            if (englishModel == null) {
                englishModel = Model(enPath)
            }

            _state.value = _state.value.copy(
                isInitialized = true,
                errorMessage = null
            )
        }.onFailure { err ->
            Log.e(TAG, "Failed to initialize Vosk models", err)
            _state.value = _state.value.copy(
                isInitialized = false,
                errorMessage = "Vosk Initialization Error: ${err.localizedMessage}"
            )
        }
    }

    fun setLanguageMode(languageMode: LanguageMode) {
        val wasListening = _state.value.isListening
        if (wasListening) {
            stopListening()
        }
        _state.value = _state.value.copy(activeLanguage = languageMode)
        if (wasListening) {
            startListening()
        }
    }

    fun startListening() {
        if (!_state.value.isInitialized) {
            _state.value = _state.value.copy(errorMessage = "Vosk models are not initialized yet.")
            return
        }
        if (_state.value.isListening) return

        try {
            stopListeningInternal()
            val selectedModel = when (_state.value.activeLanguage) {
                LanguageMode.ARABIC -> arabicModel
                LanguageMode.ENGLISH -> englishModel
            } ?: throw IllegalStateException("Selected language model is null.")

            recognizer = Recognizer(selectedModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also { service ->
                service.startListening(this)
            }

            _state.value = _state.value.copy(
                isListening = true,
                partialTranscript = "",
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk SpeechService", e)
            _state.value = _state.value.copy(
                isListening = false,
                errorMessage = "Failed to start microphone: ${e.localizedMessage}"
            )
        }
    }

    fun stopListening() {
        stopListeningInternal()
        _state.value = _state.value.copy(isListening = false)
    }

    private fun stopListeningInternal() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechService", e)
        } finally {
            speechService = null
        }
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Recognizer", e)
        } finally {
            recognizer = null
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        val partial = parseVoskJsonField(hypothesis, "partial")
        if (partial.isNotBlank()) {
            _state.value = _state.value.copy(partialTranscript = partial)
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = parseVoskJsonField(hypothesis, "text")
        if (text.isNotBlank()) {
            val lang = _state.value.activeLanguage
            _state.value = _state.value.copy(
                partialTranscript = "",
                finalTranscript = text
            )
            mainHandler.post {
                onFinalTranscript(text, lang)
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = parseVoskJsonField(hypothesis, "text")
        if (text.isNotBlank()) {
            val lang = _state.value.activeLanguage
            _state.value = _state.value.copy(
                partialTranscript = "",
                finalTranscript = text
            )
            mainHandler.post {
                onFinalTranscript(text, lang)
            }
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk recognition error", exception)
        _state.value = _state.value.copy(
            isListening = false,
            errorMessage = exception?.localizedMessage ?: "Unknown Vosk recognition error"
        )
    }

    override fun onTimeout() {
        stopListening()
    }

    private fun parseVoskJsonField(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            JSONObject(jsonStr).optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun release() {
        stopListeningInternal()
        arabicModel?.close()
        englishModel?.close()
        arabicModel = null
        englishModel = null
    }

    private data class ModelDownloadSpec(
        val folderName: String,
        val displayName: String,
        val urls: List<String>
    )

    companion object {
        private const val TAG = "OfflineSttEngine"
        private const val SAMPLE_RATE = 16000.0f
    }
}
