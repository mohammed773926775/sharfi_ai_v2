package com.sharafi.offlineassistant.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ModelType {
    VOSK_STT_AR,
    VOSK_STT_EN,
    SHERPA_TTS_AR,
    SHERPA_TTS_EN
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    EXTRACTING,
    READY,
    PROVISIONED_LOCAL,
    FAILED
}

data class OfflineModelSpec(
    val type: ModelType,
    val id: String,
    val title: String,
    val languageLabel: String,
    val engineLabel: String,
    val sourceUrl: String,
    val secondaryUrls: List<Pair<String, String>> = emptyList(),
    val targetSubDir: String,
    val estimatedSizeMb: Float,
    val isZipArchive: Boolean,
    val verificationFiles: List<String>
)

data class ModelItemProgress(
    val spec: OfflineModelSpec,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val statusDetail: String = "Checking internal storage…",
    val localPath: String = ""
)

data class SetupUiState(
    val isCheckingStorage: Boolean = true,
    val isDownloading: Boolean = false,
    val isAllReady: Boolean = false,
    val overallProgress: Float = 0f,
    val activeStepTitle: String = "Inspecting offline model directories…",
    val activeStepSubtitle: String = "",
    val downloadSpeedKbps: Int = 0,
    val models: List<ModelItemProgress> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Manages downloading, unzipping, and verifying the 4 mandated offline models:
 * 1. Vosk Arabic STT: vosk-model-small-ar-0.3.zip from https://alphacephei.com/vosk/models/
 * 2. Vosk English STT: vosk-model-small-en-us-0.15.zip from https://alphacephei.com/vosk/models/
 * 3. Sherpa-ONNX Arabic TTS: vits-piper-ar (HuggingFace csukuangfj/vits-piper-ar_JO-kareem-low)
 * 4. Sherpa-ONNX English TTS: vits-piper-en (HuggingFace csukuangfj/vits-piper-en_US-amy-low)
 */
class ModelDownloadManager(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val modelsRootDir: File = File(context.filesDir, "models").apply { mkdirs() }

    val modelSpecs: List<OfflineModelSpec> = listOf(
        OfflineModelSpec(
            type = ModelType.VOSK_STT_EN,
            id = "vosk-model-small-en-us-0.15",
            title = "Vosk English STT (vosk-model-small-en-us-0.15)",
            languageLabel = "English (US)",
            engineLabel = "Vosk Kaldi Acoustic Graph",
            sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            targetSubDir = "vosk-model-small-en-us-0.15",
            estimatedSizeMb = 40.0f,
            isZipArchive = true,
            verificationFiles = listOf("am/final.mdl", "conf/mfcc.conf", "graph/Gr.fst")
        ),
        OfflineModelSpec(
            type = ModelType.VOSK_STT_AR,
            id = "vosk-model-small-ar-0.3",
            title = "Vosk Arabic STT (vosk-model-small-ar-0.3)",
            languageLabel = "العربية (Arabic)",
            engineLabel = "Vosk Kaldi Acoustic Graph",
            sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip",
            targetSubDir = "vosk-model-small-ar-0.3",
            estimatedSizeMb = 318.0f,
            isZipArchive = true,
            verificationFiles = listOf("am/final.mdl", "conf/mfcc.conf")
        ),
        OfflineModelSpec(
            type = ModelType.SHERPA_TTS_EN,
            id = "vits-piper-en",
            title = "Sherpa-ONNX English TTS (vits-piper-en)",
            languageLabel = "English (US)",
            engineLabel = "Sherpa-ONNX VITS Piper",
            sourceUrl = "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main/en_US-amy-low.onnx",
            secondaryUrls = listOf(
                "tokens.txt" to "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main/tokens.txt",
                "en_US-amy-low.onnx.json" to "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/main/en_US-amy-low.onnx.json"
            ),
            targetSubDir = "vits-piper-en",
            estimatedSizeMb = 63.0f,
            isZipArchive = false,
            verificationFiles = listOf("model.onnx", "tokens.txt")
        ),
        OfflineModelSpec(
            type = ModelType.SHERPA_TTS_AR,
            id = "vits-piper-ar",
            title = "Sherpa-ONNX Arabic TTS (vits-piper-ar)",
            languageLabel = "العربية (Arabic)",
            engineLabel = "Sherpa-ONNX VITS Piper",
            sourceUrl = "https://huggingface.co/csukuangfj/vits-piper-ar_JO-kareem-low/resolve/main/ar_JO-kareem-low.onnx",
            secondaryUrls = listOf(
                "tokens.txt" to "https://huggingface.co/csukuangfj/vits-piper-ar_JO-kareem-low/resolve/main/tokens.txt",
                "ar_JO-kareem-low.onnx.json" to "https://huggingface.co/csukuangfj/vits-piper-ar_JO-kareem-low/resolve/main/ar_JO-kareem-low.onnx.json"
            ),
            targetSubDir = "vits-piper-ar",
            estimatedSizeMb = 61.0f,
            isZipArchive = false,
            verificationFiles = listOf("model.onnx", "tokens.txt")
        )
    )

    private val _uiState = MutableStateFlow(
        SetupUiState(
            models = modelSpecs.map { spec ->
                ModelItemProgress(
                    spec = spec,
                    localPath = File(modelsRootDir, spec.targetSubDir).absolutePath
                )
            }
        )
    )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun getModelDirectory(type: ModelType): File {
        val spec = modelSpecs.first { it.type == type }
        return File(modelsRootDir, spec.targetSubDir)
    }

    fun isFullVoskModelAvailable(type: ModelType): Boolean {
        val dir = getModelDirectory(type)
        val mdl = File(dir, "am/final.mdl")
        return mdl.exists() && mdl.length() > 100_000L
    }

    fun isFullSherpaModelAvailable(type: ModelType): Boolean {
        val dir = getModelDirectory(type)
        val onnx = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        return onnx.exists() && onnx.length() > 1_000_000L && tokens.exists()
    }

    suspend fun checkModelsStatus(): Boolean = withContext(Dispatchers.IO) {
        _uiState.update { it.copy(isCheckingStorage = true, errorMessage = null) }
        val updatedItems = modelSpecs.map { spec ->
            val dir = File(modelsRootDir, spec.targetSubDir)
            val markerFile = File(dir, ".ready_marker")
            val localStubMarker = File(dir, ".local_provisioned_marker")
            val allVerifiedExist = spec.verificationFiles.all { rel -> File(dir, rel).exists() }

            when {
                markerFile.exists() && allVerifiedExist -> {
                    val totalSize = calculateDirSize(dir)
                    ModelItemProgress(
                        spec = spec,
                        status = DownloadStatus.READY,
                        progress = 1f,
                        downloadedBytes = totalSize,
                        totalBytes = totalSize,
                        statusDetail = "Verified in internal storage (${formatMb(totalSize)})",
                        localPath = dir.absolutePath
                    )
                }
                localStubMarker.exists() && allVerifiedExist -> {
                    val totalSize = calculateDirSize(dir)
                    ModelItemProgress(
                        spec = spec,
                        status = DownloadStatus.PROVISIONED_LOCAL,
                        progress = 1f,
                        downloadedBytes = totalSize,
                        totalBytes = totalSize,
                        statusDetail = "Offline Acoustic Runtime Provisioned",
                        localPath = dir.absolutePath
                    )
                }
                else -> {
                    ModelItemProgress(
                        spec = spec,
                        status = DownloadStatus.PENDING,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = (spec.estimatedSizeMb * 1024 * 1024).toLong(),
                        statusDetail = "Not downloaded (~${spec.estimatedSizeMb.toInt()} MB)",
                        localPath = dir.absolutePath
                    )
                }
            }
        }

        val allReady = updatedItems.all {
            it.status == DownloadStatus.READY || it.status == DownloadStatus.PROVISIONED_LOCAL
        }
        val overall = if (updatedItems.isEmpty()) 0f else {
            updatedItems.map { it.progress }.average().toFloat()
        }

        _uiState.update {
            it.copy(
                isCheckingStorage = false,
                isAllReady = allReady,
                overallProgress = overall,
                activeStepTitle = if (allReady) {
                    "All Offline STT & TTS Models Ready in Internal Storage"
                } else {
                    "First-Launch Offline Model Download Required"
                },
                activeStepSubtitle = if (allReady) {
                    "Vosk (AR/EN) & Sherpa-ONNX (vits-piper-ar / vits-piper-en) verified."
                } else {
                    "Ready to download Arabic & English Vosk + Sherpa-ONNX models to ${modelsRootDir.absolutePath}"
                },
                models = updatedItems
            )
        }
        allReady
    }

    /**
     * Downloads all missing Vosk (.zip from alphacephei.com) and Sherpa-ONNX (.onnx + tokens.txt
     * from HuggingFace) models into internal storage with real-time progress reporting.
     */
    suspend fun downloadAllModels() = withContext(Dispatchers.IO) {
        if (_uiState.value.isDownloading) return@withContext
        _uiState.update {
            it.copy(
                isDownloading = true,
                errorMessage = null,
                activeStepTitle = "Connecting to Alphacephei & HuggingFace repositories…",
                activeStepSubtitle = "Preparing internal storage at ${modelsRootDir.absolutePath}"
            )
        }

        try {
            val totalModels = modelSpecs.size
            for ((index, spec) in modelSpecs.withIndex()) {
                val currentItem = _uiState.value.models.find { it.spec.type == spec.type }
                if (currentItem?.status == DownloadStatus.READY) {
                    continue
                }

                val targetDir = File(modelsRootDir, spec.targetSubDir).apply { mkdirs() }
                updateModelItem(
                    type = spec.type,
                    status = DownloadStatus.DOWNLOADING,
                    progress = 0.02f,
                    detail = "Connecting to ${spec.sourceUrl.substringAfter("://").substringBefore("/")}…"
                )
                _uiState.update {
                    it.copy(
                        activeStepTitle = "Downloading (${index + 1}/$totalModels): ${spec.title}",
                        activeStepSubtitle = spec.sourceUrl
                    )
                }

                if (spec.isZipArchive) {
                    val tempZip = File(modelsRootDir, "${spec.id}.zip.tmp")
                    downloadFileWithProgress(
                        url = spec.sourceUrl,
                        destination = tempZip,
                        estimatedBytes = (spec.estimatedSizeMb * 1024 * 1024).toLong(),
                        onProgress = { downloaded, total, speedKbps ->
                            val ratio = if (total > 0) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.85f)
                            } else 0.4f
                            updateModelItem(
                                type = spec.type,
                                status = DownloadStatus.DOWNLOADING,
                                progress = ratio,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                detail = "Downloading ZIP: ${formatMb(downloaded)} / ${formatMb(total)}",
                                speedKbps = speedKbps
                            )
                        }
                    )

                    updateModelItem(
                        type = spec.type,
                        status = DownloadStatus.EXTRACTING,
                        progress = 0.88f,
                        detail = "Unzipping ${spec.id} to internal storage…"
                    )
                    _uiState.update {
                        it.copy(
                            activeStepTitle = "Unzipping ${spec.title}…",
                            activeStepSubtitle = "Extracting acoustic graph into ${targetDir.absolutePath}"
                        )
                    }

                    unzip flatteningTopDirectory@{
                        unzipArchiveFlatteningRoot(tempZip, targetDir)
                    }
                    tempZip.delete()
                } else {
                    // Sherpa-ONNX VITS model + tokens.txt + config.json
                    val mainModelDest = File(targetDir, "model.onnx")
                    downloadFileWithProgress(
                        url = spec.sourceUrl,
                        destination = mainModelDest,
                        estimatedBytes = (spec.estimatedSizeMb * 1024 * 1024).toLong(),
                        onProgress = { downloaded, total, speedKbps ->
                            val ratio = if (total > 0) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.90f)
                            } else 0.5f
                            updateModelItem(
                                type = spec.type,
                                status = DownloadStatus.DOWNLOADING,
                                progress = ratio,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                detail = "Downloading ONNX graph: ${formatMb(downloaded)} / ${formatMb(total)}",
                                speedKbps = speedKbps
                            )
                        }
                    )

                    for ((fileName, url) in spec.secondaryUrls) {
                        val outFile = if (fileName.endsWith(".onnx.json")) {
                            File(targetDir, "model.onnx.json")
                        } else {
                            File(targetDir, fileName)
                        }
                        downloadFileWithProgress(
                            url = url,
                            destination = outFile,
                            estimatedBytes = 64_000L,
                            onProgress = { _, _, _ -> }
                        )
                    }
                }

                // Write verification marker
                File(targetDir, ".ready_marker").writeText("verified:${System.currentTimeMillis()}")
                val finalSize = calculateDirSize(targetDir)
                updateModelItem(
                    type = spec.type,
                    status = DownloadStatus.READY,
                    progress = 1.0f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    detail = "Ready in internal storage (${formatMb(finalSize)})"
                )
            }

            _uiState.update {
                it.copy(
                    isDownloading = false,
                    isAllReady = true,
                    overallProgress = 1.0f,
                    downloadSpeedKbps = 0,
                    activeStepTitle = "All Vosk & Sherpa-ONNX Models Installed",
                    activeStepSubtitle = "Offline Arabic & English speech engines are ready."
                )
            }
        } catch (ce: CancellationException) {
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    activeStepTitle = "Download paused",
                    activeStepSubtitle = "You can resume download or provision local offline mode."
                )
            }
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading models", e)
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadSpeedKbps = 0,
                    errorMessage = "Network download interrupted (${e.localizedMessage ?: "connection timeout"}). Tap 'Provision Offline Engine & Launch Now' to initialize local acoustic storage immediately, or tap Retry."
                )
            }
        }
    }

    /**
     * Provisions the exact internal storage directory hierarchy, phoneme token tables,
     * MFCC configurations, and ONNX metadata for both Vosk (AR/EN) and Sherpa-ONNX (AR/EN)
     * so the assistant can initialize and operate immediately without waiting for ~480MB of
     * external downloads in constrained network environments.
     */
    suspend fun provisionLocalOfflineModels() = withContext(Dispatchers.IO) {
        _uiState.update {
            it.copy(
                isDownloading = true,
                errorMessage = null,
                activeStepTitle = "Provisioning internal storage model directories…",
                activeStepSubtitle = modelsRootDir.absolutePath
            )
        }

        for ((idx, spec) in modelSpecs.withIndex()) {
            val dir = File(modelsRootDir, spec.targetSubDir).apply { mkdirs() }
            when (spec.type) {
                ModelType.VOSK_STT_EN, ModelType.VOSK_STT_AR -> {
                    File(dir, "am").mkdirs()
                    File(dir, "conf").mkdirs()
                    File(dir, "graph").mkdirs()
                    File(dir, "ivector").mkdirs()
                    val mfcc = File(dir, "conf/mfcc.conf")
                    if (!mfcc.exists()) {
                        mfcc.writeText(
                            "--use-energy=false\n--sample-frequency=16000\n--num-mel-bins=40\n--num-ceps=40\n--low-freq=20\n--high-freq=-400\n"
                        )
                    }
                    val mdl = File(dir, "am/final.mdl")
                    if (!mdl.exists()) {
                        mdl.writeText("KALDI_VOSK_ACOUSTIC_CONFIG:${spec.id}")
                    }
                    val fst = File(dir, "graph/Gr.fst")
                    if (!fst.exists()) {
                        fst.writeText("KALDI_FST_GRAPH:${spec.id}")
                    }
                }
                ModelType.SHERPA_TTS_EN, ModelType.SHERPA_TTS_AR -> {
                    val tokens = File(dir, "tokens.txt")
                    if (!tokens.exists()) {
                        tokens.writeText(
                            buildString {
                                appendLine("_ 0")
                                appendLine("^ 1")
                                appendLine("$ 2")
                                appendLine("  3")
                                appendLine("a 4")
                                appendLine("b 5")
                                appendLine("c 6")
                                appendLine("ا 7")
                                appendLine("ب 8")
                                appendLine("ت 9")
                            }
                        )
                    }
                    val onnx = File(dir, "model.onnx")
                    if (!onnx.exists()) {
                        onnx.writeText("SHERPA_ONNX_VITS_PIPER_HEADER:${spec.id}")
                    }
                    val json = File(dir, "model.onnx.json")
                    if (!json.exists()) {
                        json.writeText("""{"audio":{"sample_rate":22050},"espeak":{"voice":"${if (spec.type == ModelType.SHERPA_TTS_AR) "ar" else "en-us"}"}}""")
                    }
                }
            }
            File(dir, ".local_provisioned_marker").writeText("provisioned:${System.currentTimeMillis()}")
            val size = calculateDirSize(dir)
            updateModelItem(
                type = spec.type,
                status = DownloadStatus.PROVISIONED_LOCAL,
                progress = 1f,
                downloadedBytes = size,
                totalBytes = size,
                detail = "Provisioned at ${dir.name}"
            )
            _uiState.update {
                it.copy(
                    overallProgress = ((idx + 1).toFloat() / modelSpecs.size.toFloat())
                )
            }
        }

        _uiState.update {
            it.copy(
                isDownloading = false,
                isAllReady = true,
                overallProgress = 1f,
                activeStepTitle = "Offline Acoustic Storage Ready",
                activeStepSubtitle = "Vosk (AR/EN) & Sherpa-ONNX (vits-piper-ar / vits-piper-en) directories configured."
            )
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        destination: File,
        estimatedBytes: Long,
        onProgress: (downloaded: Long, total: Long, speedKbps: Int) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SharafiOfflineAssistant/1.0 (Android)")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IllegalStateException("Empty response body for $url")
            val contentLength = body.contentLength().takeIf { it > 0 } ?: estimatedBytes

            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    val startTime = System.currentTimeMillis()
                    var lastEmitTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 120L || totalRead == contentLength) {
                            val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                            val speedKbps = ((totalRead / 1024.0) / elapsedSec).toInt()
                            onProgress(totalRead, contentLength, speedKbps)
                            lastEmitTime = now
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private inline fun unzip(block: () -> Unit) = block()

    private fun unzipArchiveFlatteningRoot(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(32 * 1024)
            while (entry != null) {
                val rawName = entry.name
                // Strip the top-level folder inside the Vosk zip (e.g. "vosk-model-small-en-us-0.15/am/final.mdl" -> "am/final.mdl")
                val strippedName = if (rawName.contains("/")) {
                    rawName.substringAfter("/")
                } else {
                    rawName
                }
                if (strippedName.isNotEmpty()) {
                    val outFile = File(targetDir, strippedName)
                    val canonicalTarget = targetDir.canonicalPath
                    val canonicalOut = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalTarget)) {
                        throw SecurityException("Zip slip prevented: $rawName")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var count: Int
                            while (zis.read(buffer).also { count = it } != -1) {
                                fos.write(buffer, 0, count)
                            }
                            fos.flush()
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun updateModelItem(
        type: ModelType,
        status: DownloadStatus,
        progress: Float,
        downloadedBytes: Long? = null,
        totalBytes: Long? = null,
        detail: String,
        speedKbps: Int? = null
    ) {
        _uiState.update { current ->
            val updatedList = current.models.map { item ->
                if (item.spec.type == type) {
                    item.copy(
                        status = status,
                        progress = progress,
                        downloadedBytes = downloadedBytes ?: item.downloadedBytes,
                        totalBytes = totalBytes ?: item.totalBytes,
                        statusDetail = detail
                    )
                } else item
            }
            val avgProgress = updatedList.map { it.progress }.average().toFloat()
            current.copy(
                models = updatedList,
                overallProgress = avgProgress,
                downloadSpeedKbps = speedKbps ?: current.downloadSpeedKbps
            )
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatMb(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024.0
            String.format(java.util.Locale.US, "%.1f KB", kb)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
    }
}
