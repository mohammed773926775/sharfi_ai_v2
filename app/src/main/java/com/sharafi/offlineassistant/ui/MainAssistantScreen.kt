package com.sharafi.offlineassistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianCardBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TurquoiseSecondary
import com.sharafi.offlineassistant.executor.ActionExecutionResult
import com.sharafi.offlineassistant.executor.ActionPlugin
import com.sharafi.offlineassistant.executor.LiveHardwareState
import com.sharafi.offlineassistant.nlu.CompoundNluResult
import com.sharafi.offlineassistant.nlu.ParsedIntent
import com.sharafi.offlineassistant.stt.AssistantLanguage
import com.sharafi.offlineassistant.stt.SttEngineStatus
import com.sharafi.offlineassistant.tts.TtsEngineStatus
import com.sharafi.offlineassistant.viewmodel.AssistantStage
import com.sharafi.offlineassistant.viewmodel.CommandExecutionLogEntry
import com.sharafi.offlineassistant.viewmodel.MainAssistantUiState
import com.sharafi.offlineassistant.viewmodel.PresetVoiceCommand
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(
    uiState: MainAssistantUiState,
    sttStatus: SttEngineStatus,
    ttsStatus: TtsEngineStatus,
    hardwareState: LiveHardwareState,
    presetCommands: List<PresetVoiceCommand>,
    onToggleListening: () -> Unit,
    onToggleLanguage: () -> Unit,
    onCommandInputChanged: (String) -> Unit,
    onSubmitCommand: (String) -> Unit,
    onReplayResponse: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onTogglePluginSheet: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var micPermissionNotice by remember { mutableStateOf<String?>(null) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            micPermissionNotice = null
            onToggleListening()
        } else {
            micPermissionNotice = "Microphone permission declined. You can still test all offline voice commands using the Quick Voice Command chips or command bar below."
        }
    }

    val handleMicClick = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted || sttStatus.isListening) {
            onToggleListening()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = ObsidianBackground,
        bottomBar = {
            CommandInputBar(
                inputText = uiState.commandInputText,
                onInputChanged = onCommandInputChanged,
                onSend = { onSubmitCommand(uiState.commandInputText) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .testTag("main_assistant_list"),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Top HUD Header
                item {
                    AssistantHudHeader(
                        activeLanguage = uiState.activeLanguage,
                        sttEngineLabel = sttStatus.engineModeLabel,
                        ttsEngineLabel = ttsStatus.activeEngineLabel,
                        pluginCount = uiState.registeredPlugins.size,
                        onToggleLanguage = onToggleLanguage,
                        onOpenModelSetup = onOpenModelSetup,
                        onOpenPlugins = { onTogglePluginSheet(true) }
                    )
                }

                // 2. Live Device Hardware Telemetry Strip
                item {
                    HardwareTelemetryStrip(
                        hardwareState = hardwareState,
                        onQuickAction = onSubmitCommand
                    )
                }

                // 3. Central Voice Orb & Live Acoustic Waveform Visualizer
                item {
                    VoiceAssistantOrbCard(
                        stage = uiState.stage,
                        sttStatus = sttStatus,
                        ttsStatus = ttsStatus,
                        micPermissionNotice = micPermissionNotice,
                        onMicClick = handleMicClick
                    )
                }

                // 4. Quick Voice Simulation & Compound Command Presets (EN & AR)
                item {
                    QuickVoicePresetsSection(
                        activeLanguage = uiState.activeLanguage,
                        presets = presetCommands,
                        onSelectPreset = onSubmitCommand
                    )
                }

                // 5. Recognized Speech & Sherpa-ONNX Spoken Response Card
                item {
                    RecognizedSpeechAndTtsCard(
                        recognizedText = uiState.lastRecognizedText,
                        partialTranscript = sttStatus.partialTranscript,
                        spokenResponse = uiState.lastSpokenResponse,
                        isSpeaking = ttsStatus.isSpeaking,
                        activeTtsLabel = ttsStatus.activeEngineLabel,
                        onReplayResponse = onReplayResponse
                    )
                }

                // 6. Active NLU Intent Breakdown & Plugin Execution Status
                if (uiState.lastNluResult != null && uiState.lastExecutionReport != null) {
                    item {
                        NluAndExecutionStatusCard(
                            nluResult = uiState.lastNluResult,
                            results = uiState.lastExecutionReport.results,
                            totalTimeMs = uiState.lastExecutionReport.totalExecutionTimeMs
                        )
                    }
                }

                // 7. Recent Execution History Log
                if (uiState.history.size > 1) {
                    item {
                        Text(
                            text = "RECENT OFFLINE EXECUTION LOG",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(uiState.history.drop(1).take(6), key = { it.id }) { entry ->
                        HistoryLogCard(
                            entry = entry,
                            onRerun = { onSubmitCommand(entry.utterance) }
                        )
                    }
                }
            }
        }
    }

    if (uiState.showPluginRegistrySheet) {
        BackHandler {
            onTogglePluginSheet(false)
        }
        ModalBottomSheet(
            onDismissRequest = { onTogglePluginSheet(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ObsidianSurface,
            contentColor = TextPrimary
        ) {
            PluginRegistryBottomSheetContent(
                plugins = uiState.registeredPlugins
            )
        }
    }
}

@Composable
private fun AssistantHudHeader(
    activeLanguage: AssistantLanguage,
    sttEngineLabel: String,
    ttsEngineLabel: String,
    pluginCount: Int,
    onToggleLanguage: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.brand_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = CyanPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Surface(
                            color = EmeraldSuccess.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = EmeraldSuccess,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.offline_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldSuccess
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.brand_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenPlugins,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("plugin_registry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = stringResource(R.string.plugin_registry_desc),
                            tint = TurquoiseSecondary
                        )
                    }
                    IconButton(
                        onClick = onOpenModelSetup,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("model_setup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = stringResource(R.string.model_manager_desc),
                            tint = CyanPrimary
                        )
                    }
                }
            }

            // Engine & Language Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = CyanPrimary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleLanguage() }
                        .minimumInteractiveComponentSize()
                        .testTag("language_toggle_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (activeLanguage == AssistantLanguage.ARABIC) {
                                "AR • العربية"
                            } else {
                                "EN • English"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = CyanPrimary
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "STT: $sttEngineLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "TTS: $ttsEngineLabel • $pluginCount Plugins",
                        style = MaterialTheme.typography.labelSmall,
                        color = TurquoiseSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareTelemetryStrip(
    hardwareState: LiveHardwareState,
    onQuickAction: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Torch / Flashlight Pill
            TelemetryMetricPill(
                icon = Icons.Default.FlashlightOn,
                label = "TORCH",
                value = if (hardwareState.isFlashlightOn) "ON" else "OFF",
                accent = if (hardwareState.isFlashlightOn) CyanPrimary else TextMuted,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (hardwareState.isFlashlightOn) {
                            onQuickAction("Turn off the flashlight")
                        } else {
                            onQuickAction("Turn on the flashlight")
                        }
                    }
            )

            // Battery Pill
            TelemetryMetricPill(
                icon = Icons.Default.BatteryChargingFull,
                label = "BATTERY",
                value = "${hardwareState.batteryLevelPercent}%",
                accent = EmeraldSuccess,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onQuickAction("Tell me the battery level") }
            )

            // Volume Pill
            TelemetryMetricPill(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "VOLUME",
                value = "${hardwareState.mediaVolumePercent}%",
                accent = TurquoiseSecondary,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onQuickAction("Set volume to 80 percent") }
            )

            // Wi-Fi Pill
            TelemetryMetricPill(
                icon = Icons.Default.Wifi,
                label = "WI-FI",
                value = if (hardwareState.isWifiConnected) "ACTIVE" else "OFF",
                accent = if (hardwareState.isWifiConnected) CyanPrimary else AmberWarning,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onQuickAction("Check Wi-Fi status") }
            )
        }

        AnimatedVisibility(visible = hardwareState.activeTimerSecondsRemaining > 0) {
            val mins = hardwareState.activeTimerSecondsRemaining / 60
            val secs = hardwareState.activeTimerSecondsRemaining % 60
            Surface(
                color = TurquoiseSecondary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, TurquoiseSecondary.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = TurquoiseSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ACTIVE VOICE TIMER (${hardwareState.activeTimerLabel})",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", mins, secs),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = TurquoiseSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ObsidianSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier.clip(RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VoiceAssistantOrbCard(
    stage: AssistantStage,
    sttStatus: SttEngineStatus,
    ttsStatus: TtsEngineStatus,
    micPermissionNotice: String?,
    onMicClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_radius"
    )

    val stageTitle = when (stage) {
        AssistantStage.IDLE -> "TAP MICROPHONE OR SELECT A VOICE COMMAND"
        AssistantStage.LISTENING -> "LISTENING OFFLINE VIA VOSK (16kHz PCM)…"
        AssistantStage.PARSING_NLU -> "PARSING RULE-BASED COMPOUND NLU…"
        AssistantStage.EXECUTING_ACTIONS -> "DISPATCHING SYSTEM ACTION PLUGINS…"
        AssistantStage.SPEAKING -> "SYNTHESIZING VOICE VIA SHERPA-ONNX TTS…"
    }

    val stageColor = when (stage) {
        AssistantStage.IDLE -> CyanPrimary
        AssistantStage.LISTENING -> TurquoiseSecondary
        AssistantStage.PARSING_NLU, AssistantStage.EXECUTING_ACTIONS -> AmberWarning
        AssistantStage.SPEAKING -> CyanPrimary
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(
            width = if (sttStatus.isListening || ttsStatus.isSpeaking) 1.5.dp else 1.dp,
            color = if (sttStatus.isListening || ttsStatus.isSpeaking) stageColor else ObsidianCardBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Stage Badge
            Surface(
                color = stageColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, stageColor.copy(alpha = 0.45f))
            ) {
                Text(
                    text = stageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = stageColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            // Large Interactive Microphone Orb with Concentric Sonic Rings
            Box(
                modifier = Modifier
                    .size(164.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2.9f
                    val activeFactor = if (sttStatus.isListening || ttsStatus.isSpeaking) {
                        pulseRadius + sttStatus.audioRmsLevel * 0.25f
                    } else {
                        0.92f
                    }

                    // Outer glow ring
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                stageColor.copy(alpha = 0.28f),
                                stageColor.copy(alpha = 0.0f)
                            ),
                            center = center,
                            radius = baseRadius * 1.45f * activeFactor
                        ),
                        radius = baseRadius * 1.45f * activeFactor,
                        center = center
                    )

                    // Sonic ring 1
                    drawCircle(
                        color = stageColor.copy(alpha = 0.45f),
                        radius = baseRadius * 1.18f * activeFactor,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Sonic ring 2
                    drawCircle(
                        color = TurquoiseSecondary.copy(alpha = 0.30f),
                        radius = baseRadius * 1.34f * activeFactor,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                FloatingActionButton(
                    onClick = onMicClick,
                    shape = CircleShape,
                    containerColor = if (sttStatus.isListening) TurquoiseSecondary else CyanPrimary,
                    contentColor = Color(0xFF001F24),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 10.dp),
                    modifier = Modifier
                        .size(92.dp)
                        .testTag("mic_listen_button")
                ) {
                    Icon(
                        imageVector = if (sttStatus.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.mic_button_desc),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Live 24-Band Acoustic Waveform Visualizer
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 8.dp)
            ) {
                val bars = sttStatus.waveformBars
                val count = bars.size.coerceAtLeast(1)
                val spacing = 6.dp.toPx()
                val totalSpacing = spacing * (count - 1)
                val barWidth = ((size.width - totalSpacing) / count).coerceAtLeast(3f)

                bars.forEachIndexed { index, amplitude ->
                    val effectiveAmp = if (sttStatus.isListening || ttsStatus.isSpeaking) {
                        amplitude.coerceIn(0.12f, 1f)
                    } else {
                        0.15f
                    }
                    val barHeight = size.height * effectiveAmp
                    val x = index * (barWidth + spacing)
                    val y = (size.height - barHeight) / 2f

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(CyanPrimary, TurquoiseSecondary)
                        ),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            if (micPermissionNotice != null) {
                Text(
                    text = micPermissionNotice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberWarning
                )
            }
        }
    }
}

@Composable
private fun QuickVoicePresetsSection(
    activeLanguage: AssistantLanguage,
    presets: List<PresetVoiceCommand>,
    onSelectPreset: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activeLanguage == AssistantLanguage.ARABIC) {
                        "أوامر صوتية فورية (مفردة ومركبة)"
                    } else {
                        "INSTANT VOICE UTTERANCES (COMPOUND & SINGLE)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(presets) { preset ->
                val isAr = activeLanguage == AssistantLanguage.ARABIC
                val label = if (isAr) preset.labelAr else preset.labelEn
                val utterance = if (isAr) preset.utteranceAr else preset.utteranceEn
                val borderColor = if (preset.isCompound) TurquoiseSecondary else ObsidianCardBorder

                Surface(
                    color = if (preset.isCompound) {
                        TurquoiseSecondary.copy(alpha = 0.12f)
                    } else {
                        ObsidianSurfaceElevated
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectPreset(utterance) }
                        .minimumInteractiveComponentSize()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (preset.isCompound) TurquoiseSecondary else CyanPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "\"$utterance\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognizedSpeechAndTtsCard(
    recognizedText: String,
    partialTranscript: String,
    spokenResponse: String,
    isSpeaking: Boolean,
    activeTtsLabel: String,
    onReplayResponse: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Recognized Speech Row
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "RECOGNIZED SPEECH (VOSK OFFLINE STT)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary
                )
                Text(
                    text = when {
                        partialTranscript.isNotBlank() -> partialTranscript
                        recognizedText.isNotBlank() -> "\"$recognizedText\""
                        else -> "No voice command captured yet — tap the microphone or pick a command chip above."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (recognizedText.isNotBlank() || partialTranscript.isNotBlank()) {
                        TextPrimary
                    } else {
                        TextMuted
                    },
                    modifier = Modifier.testTag("recognized_speech_text")
                )
            }

            // Spoken TTS Response Row
            if (spokenResponse.isNotBlank()) {
                Surface(
                    color = ObsidianSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, TurquoiseSecondary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = TurquoiseSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isSpeaking) "SPEAKING NOW • $activeTtsLabel" else activeTtsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TurquoiseSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = spokenResponse,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                modifier = Modifier.testTag("tts_spoken_response_text")
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onReplayResponse,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("replay_tts_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.speaker_button_desc),
                                tint = CyanPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NluAndExecutionStatusCard(
    nluResult: CompoundNluResult,
    results: List<ActionExecutionResult>,
    totalTimeMs: Long
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("execution_status_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (nluResult.isCompound) {
                            "COMPOUND NLU PIPELINE (${nluResult.intents.size} INTENTS)"
                        } else {
                            "SINGLE INTENT NLU PIPELINE"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = CyanPrimary
                    )
                }

                Text(
                    text = "NLU ${nluResult.parseLatencyMs}ms • EXEC ${totalTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = TurquoiseSecondary
                )
            }

            nluResult.intents.zip(results).forEach { (intent, execResult) ->
                IntentClauseExecutionItem(
                    intent = intent,
                    result = execResult
                )
            }
        }
    }
}

@Composable
private fun IntentClauseExecutionItem(
    intent: ParsedIntent,
    result: ActionExecutionResult
) {
    val badgeColor = if (result.success) EmeraldSuccess else CoralError

    Surface(
        color = ObsidianSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CLAUSE #${intent.clauseIndex + 1} • ${intent.domain.displayNameEn.uppercase(Locale.US)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                }
                Text(
                    text = intent.matchedRule,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary
                )
            }

            Text(
                text = "Clause: \"${intent.rawClause}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (intent.parameters.isNotEmpty()) {
                Text(
                    text = "Slots: ${intent.parameters.entries.joinToString { "${it.key}=${it.value}" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TurquoiseSecondary
                )
            }

            Text(
                text = "Plugin [${result.pluginId}] -> ${result.technicalSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun HistoryLogCard(
    entry: CommandExecutionLogEntry,
    onRerun: () -> Unit
) {
    Surface(
        color = ObsidianSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ObsidianCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onRerun() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "\"${entry.utterance}\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.spokenOutput,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = CyanPrimary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${entry.nluResult.intents.size} Intent(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CommandInputBar(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChanged,
                placeholder = {
                    Text(
                        text = stringResource(R.string.command_input_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = ObsidianCardBorder,
                    focusedContainerColor = ObsidianBackground,
                    unfocusedContainerColor = ObsidianBackground,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input_field")
            )
            Spacer(modifier = Modifier.width(10.dp))
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary)
                    .testTag("send_command_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.execute_command_desc),
                    tint = Color(0xFF001F24)
                )
            }
        }
    }
}

@Composable
private fun PluginRegistryBottomSheetContent(
    plugins: List<ActionPlugin>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.SettingsSuggest,
                contentDescription = null,
                tint = CyanPrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "SystemActionExecutor Plugin Registry",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = "${plugins.size} modular ActionPlugin implementations registered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(plugins, key = { it.pluginId }) { plugin ->
                Surface(
                    color = ObsidianSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, ObsidianCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${plugin.domain.displayNameEn} • ${plugin.domain.displayNameAr}",
                                style = MaterialTheme.typography.titleMedium,
                                color = CyanPrimary
                            )
                            Text(
                                text = plugin.pluginId,
                                style = MaterialTheme.typography.labelSmall,
                                color = TurquoiseSecondary
                            )
                        }
                        Text(
                            text = plugin.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
