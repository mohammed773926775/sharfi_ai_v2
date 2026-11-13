package com.sharafi.offlineassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sharafi.offlineassistant.nlu.NluEngine
import com.sharafi.offlineassistant.stt.OfflineSttEngine
import com.sharafi.offlineassistant.stt.OfflineSttEngine.LanguageMode
import com.sharafi.offlineassistant.system.SystemActionExecutor
import com.sharafi.offlineassistant.tts.OfflineTtsEngine
import com.sharafi.offlineassistant.ui.ExecutionLogEntry
import com.sharafi.offlineassistant.ui.MainScreen
import com.sharafi.offlineassistant.ui.SetupScreen
import com.sharafi.offlineassistant.ui.SetupUiState
import com.sharafi.offlineassistant.ui.theme.SharafiAiTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var sttEngine: OfflineSttEngine
    private lateinit var ttsEngine: OfflineTtsEngine
    private lateinit var nluEngine: NluEngine
    private lateinit var actionExecutor: SystemActionExecutor

    private var setupState by mutableStateOf(SetupUiState())
    private var isSetupComplete by mutableStateOf(false)
    private var executionStatus by mutableStateOf("Ready for offline voice commands.")
    private val executionLogs = mutableStateListOf<ExecutionLogEntry>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAndPrepareOfflineModels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nluEngine = NluEngine()
        actionExecutor = SystemActionExecutor(applicationContext)
        ttsEngine = OfflineTtsEngine(applicationContext)
        sttEngine = OfflineSttEngine(applicationContext) { transcript, langMode ->
            handleVoiceCommand(transcript, langMode == LanguageMode.ARABIC)
        }

        requestRequiredPermissions()

        setContent {
            SharafiAiTheme {
                val sttState by sttEngine.state.collectAsState()

                if (!isSetupComplete) {
                    SetupScreen(
                        uiState = setupState,
                        onRetryDownload = { checkAndPrepareOfflineModels() }
                    )
                } else {
                    MainScreen(
                        isListening = sttState.isListening,
                        activeLanguage = sttState.activeLanguage,
                        partialSpeech = sttState.partialTranscript,
                        finalSpeech = sttState.finalTranscript,
                        executionStatus = sttState.errorMessage ?: executionStatus,
                        logs = executionLogs,
                        onToggleListening = {
                            if (sttState.isListening) {
                                sttEngine.stopListening()
                            } else {
                                sttEngine.startListening()
                            }
                        },
                        onSwitchLanguage = { newLang ->
                            sttEngine.setLanguageMode(newLang)
                        },
                        onManualCommandSubmit = { manualText ->
                            val isArabic = manualText.any { it in '\u0600'..'\u06FF' }
                            handleVoiceCommand(manualText, isArabic)
                        }
                    )
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            checkAndPrepareOfflineModels()
        }
    }

    private fun checkAndPrepareOfflineModels() {
        lifecycleScope.launch {
            val voskReady = sttEngine.areModelsDownloaded()
            val sherpaReady = ttsEngine.areModelsDownloaded()

            setupState = setupState.copy(
                isDownloading = true,
                voskDownloaded = voskReady,
                sherpaDownloaded = sherpaReady,
                errorText = null,
                statusText = "Checking Vosk & Sherpa-ONNX models..."
            )

            // 1. Download Vosk Arabic + English models if missing
            if (!voskReady) {
                val sttResult = sttEngine.downloadModelsIfNeeded { progress, status ->
                    setupState = setupState.copy(
                        overallProgress = progress * 0.5f,
                        statusText = status
                    )
                }
                if (sttResult.isFailure) {
                    setupState = setupState.copy(
                        isDownloading = false,
                        errorText = "Vosk Download Failed: ${sttResult.exceptionOrNull()?.localizedMessage}"
                    )
                    return@launch
                }
            }
            setupState = setupState.copy(voskDownloaded = true, overallProgress = 0.5f)

            // 2. Download Sherpa-ONNX Arabic + English models if missing
            if (!sherpaReady) {
                val ttsResult = ttsEngine.downloadModelsIfNeeded { progress, status ->
                    setupState = setupState.copy(
                        overallProgress = 0.5f + (progress * 0.5f),
                        statusText = status
                    )
                }
                if (ttsResult.isFailure) {
                    setupState = setupState.copy(
                        isDownloading = false,
                        errorText = "Sherpa-ONNX Download Failed: ${ttsResult.exceptionOrNull()?.localizedMessage}"
                    )
                    return@launch
                }
            }
            setupState = setupState.copy(
                sherpaDownloaded = true,
                overallProgress = 0.95f,
                statusText = "Loading models into memory..."
            )

            // 3. Initialize native engines
            sttEngine.initializeModels()
            ttsEngine.initializeModels()

            setupState = setupState.copy(
                isDownloading = false,
                overallProgress = 1.0f,
                statusText = "All offline models initialized."
            )
            isSetupComplete = true
        }
    }

    private fun handleVoiceCommand(utterance: String, preferArabic: Boolean) {
        val commands = nluEngine.parse(utterance)
        val results = actionExecutor.executeCommands(commands, preferArabic)
        val combinedResponse = results.joinToString(separator = " ") { it.spokenResponse }
        val allSucceeded = results.all { it.success }

        executionStatus = combinedResponse
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        executionLogs.add(
            0,
            ExecutionLogEntry(
                timestamp = timeFmt,
                recognizedSpeech = utterance,
                executionSummary = combinedResponse,
                isSuccess = allSucceeded
            )
        )

        ttsEngine.speak(combinedResponse, preferArabic)
    }

    override fun onDestroy() {
        sttEngine.release()
        ttsEngine.release()
        super.onDestroy()
    }
}
