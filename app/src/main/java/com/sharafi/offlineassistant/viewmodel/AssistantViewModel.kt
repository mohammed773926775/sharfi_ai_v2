package com.sharafi.offlineassistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sharafi.offlineassistant.executor.ActionPlugin
import com.sharafi.offlineassistant.executor.CompoundExecutionReport
import com.sharafi.offlineassistant.executor.LiveHardwareState
import com.sharafi.offlineassistant.executor.SystemActionExecutor
import com.sharafi.offlineassistant.model.ModelDownloadManager
import com.sharafi.offlineassistant.model.SetupUiState
import com.sharafi.offlineassistant.nlu.CompoundNluResult
import com.sharafi.offlineassistant.nlu.NluEngine
import com.sharafi.offlineassistant.stt.AssistantLanguage
import com.sharafi.offlineassistant.stt.OfflineSttEngine
import com.sharafi.offlineassistant.stt.SttEngineStatus
import com.sharafi.offlineassistant.tts.OfflineTtsEngine
import com.sharafi.offlineassistant.tts.TtsEngineStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppScreen {
    SETUP,
    MAIN_ASSISTANT
}

enum class AssistantStage {
    IDLE,
    LISTENING,
    PARSING_NLU,
    EXECUTING_ACTIONS,
    SPEAKING
}

data class CommandExecutionLogEntry(
    val id: Long,
    val utterance: String,
    val nluResult: CompoundNluResult,
    val executionReport: CompoundExecutionReport,
    val spokenOutput: String
)

data class PresetVoiceCommand(
    val labelEn: String,
    val labelAr: String,
    val utteranceEn: String,
    val utteranceAr: String,
    val isCompound: Boolean = false
)

data class MainAssistantUiState(
    val currentScreen: AppScreen = AppScreen.SETUP,
    val stage: AssistantStage = AssistantStage.IDLE,
    val activeLanguage: AssistantLanguage = AssistantLanguage.ENGLISH,
    val commandInputText: String = "",
    val lastRecognizedText: String = "",
    val lastNluResult: CompoundNluResult? = null,
    val lastExecutionReport: CompoundExecutionReport? = null,
    val lastSpokenResponse: String = "",
    val history: List<CommandExecutionLogEntry> = emptyList(),
    val showPluginRegistrySheet: Boolean = false,
    val registeredPlugins: List<ActionPlugin> = emptyList()
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    val modelDownloadManager = ModelDownloadManager(application)
    val nluEngine = NluEngine()
    val actionExecutor = SystemActionExecutor(application)
    val ttsEngine = OfflineTtsEngine(application, modelDownloadManager)
    val sttEngine = OfflineSttEngine(
        context = application,
        modelDownloadManager = modelDownloadManager,
        onFinalSpeechRecognized = { recognizedUtterance ->
            processUtterance(recognizedUtterance)
        }
    )

    val setupState: StateFlow<SetupUiState> = modelDownloadManager.uiState
    val sttStatus: StateFlow<SttEngineStatus> = sttEngine.status
    val ttsStatus: StateFlow<TtsEngineStatus> = ttsEngine.status
    val hardwareState: StateFlow<LiveHardwareState> = actionExecutor.hardwareState

    private val _uiState = MutableStateFlow(
        MainAssistantUiState(
            registeredPlugins = actionExecutor.getRegisteredPlugins()
        )
    )
    val uiState: StateFlow<MainAssistantUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    val presetCommands: List<PresetVoiceCommand> = listOf(
        PresetVoiceCommand(
            labelEn = "⚡ Compound: Flashlight + Battery",
            labelAr = "⚡ مركب: الكشاف + البطارية",
            utteranceEn = "Turn on the flashlight and tell me the battery",
            utteranceAr = "شغل الكشاف وكم نسبة البطارية",
            isCompound = true
        ),
        PresetVoiceCommand(
            labelEn = "⚡ Compound: Volume 80% + 2m Timer",
            labelAr = "⚡ مركب: الصوت 80% + مؤقت دقيقتين",
            utteranceEn = "Set volume to 80 percent and set a timer for 2 minutes",
            utteranceAr = "اجعل الصوت 80 بالمئة ثم اضبط مؤقت لمدة 2 دقائق",
            isCompound = true
        ),
        PresetVoiceCommand(
            labelEn = "⚡ Compound: Alarm 7:30 AM + Play Music",
            labelAr = "⚡ مركب: منبه 7:30 + تشغيل الموسيقى",
            utteranceEn = "Set alarm for 7:30 am and play music",
            utteranceAr = "اضبط منبه الساعة 7:30 صباحا وشغل الموسيقى",
            isCompound = true
        ),
        PresetVoiceCommand(
            labelEn = "🔦 Turn Off Flashlight",
            labelAr = "🔦 إيقاف الكشاف",
            utteranceEn = "Turn off the flashlight",
            utteranceAr = "اطفئ الكشاف الضوئي"
        ),
        PresetVoiceCommand(
            labelEn = "🔋 Check Battery Telemetry",
            labelAr = "🔋 فحص حالة البطارية",
            utteranceEn = "What is my battery level",
            utteranceAr = "كم نسبة شحن البطارية"
        ),
        PresetVoiceCommand(
            labelEn = "📶 Wi-Fi Control Panel",
            labelAr = "📶 إعدادات الواي فاي",
            utteranceEn = "Turn on Wi-Fi",
            utteranceAr = "شغل الواي فاي"
        ),
        PresetVoiceCommand(
            labelEn = "🦷 Bluetooth Settings",
            labelAr = "🦷 فتح البلوتوث",
            utteranceEn = "Enable Bluetooth",
            utteranceAr = "شغل البلوتوث"
        ),
        PresetVoiceCommand(
            labelEn = "📷 Take Photo (Camera)",
            labelAr = "📷 التقاط صورة بالكاميرا",
            utteranceEn = "Open camera and take a photo",
            utteranceAr = "افتح الكاميرا والتقط صورة"
        ),
        PresetVoiceCommand(
            labelEn = "💬 Send SMS Message",
            labelAr = "💬 إرسال رسالة نصية",
            utteranceEn = "Send message to 5550199 saying I am arriving in 10 minutes",
            utteranceAr = "أرسل رسالة إلى 0501234567 تقول سأصل بعد عشر دقائق"
        ),
        PresetVoiceCommand(
            labelEn = "📞 Dial Contact",
            labelAr = "📞 إجراء مكالمة",
            utteranceEn = "Call 5550144",
            utteranceAr = "اتصل على 0559876543"
        ),
        PresetVoiceCommand(
            labelEn = "🧮 Open Calculator App",
            labelAr = "🧮 فتح الآلة الحاسبة",
            utteranceEn = "Open Calculator",
            utteranceAr = "افتح الآلة الحاسبة"
        ),
        PresetVoiceCommand(
            labelEn = "🖥️ Display Settings",
            labelAr = "🖥️ إعدادات الشاشة",
            utteranceEn = "Open display settings",
            utteranceAr = "افتح إعدادات الشاشة"
        )
    )

    init {
        viewModelScope.launch {
            val allReady = modelDownloadManager.checkModelsStatus()
            if (allReady) {
                initializeSpeechEnginesAndOpenMain()
            } else {
                // Mandated first-launch behavior: automatically start downloading Vosk & Sherpa-ONNX models
                startModelDownloads()
            }
        }

        // Observe STT & TTS state to keep AssistantStage synchronized
        viewModelScope.launch {
            sttEngine.status.collect { stt ->
                if (stt.isListening) {
                    _uiState.update { it.copy(stage = AssistantStage.LISTENING) }
                } else if (_uiState.value.stage == AssistantStage.LISTENING) {
                    _uiState.update { it.copy(stage = AssistantStage.IDLE) }
                }
            }
        }

        viewModelScope.launch {
            ttsEngine.status.collect { tts ->
                if (tts.isSpeaking) {
                    _uiState.update { it.copy(stage = AssistantStage.SPEAKING) }
                } else if (_uiState.value.stage == AssistantStage.SPEAKING) {
                    _uiState.update { it.copy(stage = AssistantStage.IDLE) }
                }
            }
        }

        // Countdown timer ticker for active voice timers
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                actionExecutor.tickCountdownTimer()
            }
        }
    }

    fun startModelDownloads() {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            modelDownloadManager.downloadAllModels()
            if (modelDownloadManager.uiState.value.isAllReady) {
                initializeSpeechEnginesAndOpenMain()
            }
        }
    }

    fun provisionLocalAndLaunch() {
        downloadJob?.cancel()
        viewModelScope.launch {
            modelDownloadManager.provisionLocalOfflineModels()
            initializeSpeechEnginesAndOpenMain()
        }
    }

    fun openSetupScreen() {
        sttEngine.stopListening()
        _uiState.update { it.copy(currentScreen = AppScreen.SETUP) }
    }

    fun openMainScreen() {
        viewModelScope.launch {
            initializeSpeechEnginesAndOpenMain()
        }
    }

    private suspend fun initializeSpeechEnginesAndOpenMain() {
        sttEngine.initializeModels(_uiState.value.activeLanguage)
        ttsEngine.initializeSherpaModels()
        _uiState.update { it.copy(currentScreen = AppScreen.MAIN_ASSISTANT) }
    }

    fun toggleLanguage() {
        val nextLang = if (_uiState.value.activeLanguage == AssistantLanguage.ENGLISH) {
            AssistantLanguage.ARABIC
        } else {
            AssistantLanguage.ENGLISH
        }
        selectLanguage(nextLang)
    }

    fun selectLanguage(language: AssistantLanguage) {
        _uiState.update { it.copy(activeLanguage = language) }
        viewModelScope.launch {
            sttEngine.switchLanguage(language)
        }
    }

    fun toggleListening() {
        if (sttEngine.status.value.isListening) {
            sttEngine.stopListening()
        } else {
            ttsEngine.stopSpeaking()
            sttEngine.startListening()
        }
    }

    fun updateCommandInput(text: String) {
        _uiState.update { it.copy(commandInputText = text) }
    }

    fun submitTypedOrPresetCommand(command: String = _uiState.value.commandInputText) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(commandInputText = "") }
        sttEngine.submitRecognizedUtterance(trimmed)
    }

    fun processUtterance(utterance: String) {
        val cleaned = utterance.trim()
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    stage = AssistantStage.PARSING_NLU,
                    lastRecognizedText = cleaned
                )
            }

            // 1. Deterministic NLU Parsing (supports single & compound commands in EN & AR)
            val nluResult = nluEngine.parse(cleaned)
            _uiState.update {
                it.copy(
                    stage = AssistantStage.EXECUTING_ACTIONS,
                    lastNluResult = nluResult
                )
            }

            // 2. Execute all parsed intents sequentially through SystemActionExecutor plugins
            val report = actionExecutor.executeCompound(nluResult.intents)
            val preferArabic = nluResult.isArabic || (_uiState.value.activeLanguage == AssistantLanguage.ARABIC)
            val spokenResponse = report.combinedSpokenResponse(preferArabic)

            val logEntry = CommandExecutionLogEntry(
                id = System.currentTimeMillis(),
                utterance = cleaned,
                nluResult = nluResult,
                executionReport = report,
                spokenOutput = spokenResponse
            )

            _uiState.update { state ->
                state.copy(
                    stage = AssistantStage.SPEAKING,
                    lastExecutionReport = report,
                    lastSpokenResponse = spokenResponse,
                    history = (listOf(logEntry) + state.history).take(25)
                )
            }

            // 3. Speak via OfflineTtsEngine (Sherpa-ONNX VITS with fallback to Native TTS)
            ttsEngine.speak(spokenResponse, forceArabic = preferArabic)
        }
    }

    fun replayLastResponse() {
        val text = _uiState.value.lastSpokenResponse
        if (text.isNotBlank()) {
            val isAr = nluEngine.containsArabic(text) || _uiState.value.activeLanguage == AssistantLanguage.ARABIC
            ttsEngine.speak(text, forceArabic = isAr)
        }
    }

    fun togglePluginRegistrySheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showPluginRegistrySheet = show,
                registeredPlugins = actionExecutor.getRegisteredPlugins()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sttEngine.release()
        ttsEngine.release()
    }
}
