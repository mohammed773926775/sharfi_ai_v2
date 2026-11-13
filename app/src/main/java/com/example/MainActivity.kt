package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.sharafi.offlineassistant.ui.MainAssistantScreen
import com.sharafi.offlineassistant.ui.SetupScreen
import com.sharafi.offlineassistant.viewmodel.AppScreen
import com.sharafi.offlineassistant.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SharafiOfflineAssistantApp()
            }
        }
    }
}

@Composable
fun SharafiOfflineAssistantApp(
    assistantViewModel: AssistantViewModel = viewModel()
) {
    val uiState by assistantViewModel.uiState.collectAsStateWithLifecycle()
    val setupState by assistantViewModel.setupState.collectAsStateWithLifecycle()
    val sttStatus by assistantViewModel.sttStatus.collectAsStateWithLifecycle()
    val ttsStatus by assistantViewModel.ttsStatus.collectAsStateWithLifecycle()
    val hardwareState by assistantViewModel.hardwareState.collectAsStateWithLifecycle()

    Crossfade(
        targetState = uiState.currentScreen,
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            AppScreen.SETUP -> {
                SetupScreen(
                    setupState = setupState,
                    canNavigateBack = setupState.isAllReady,
                    onStartDownload = assistantViewModel::startModelDownloads,
                    onFastTrackProvision = assistantViewModel::provisionLocalAndLaunch,
                    onContinueToMain = assistantViewModel::openMainScreen
                )
            }
            AppScreen.MAIN_ASSISTANT -> {
                MainAssistantScreen(
                    uiState = uiState,
                    sttStatus = sttStatus,
                    ttsStatus = ttsStatus,
                    hardwareState = hardwareState,
                    presetCommands = assistantViewModel.presetCommands,
                    onToggleListening = assistantViewModel::toggleListening,
                    onToggleLanguage = assistantViewModel::toggleLanguage,
                    onCommandInputChanged = assistantViewModel::updateCommandInput,
                    onSubmitCommand = assistantViewModel::submitTypedOrPresetCommand,
                    onReplayResponse = assistantViewModel::replayLastResponse,
                    onOpenModelSetup = assistantViewModel::openSetupScreen,
                    onTogglePluginSheet = assistantViewModel::togglePluginRegistrySheet
                )
            }
        }
    }
}
