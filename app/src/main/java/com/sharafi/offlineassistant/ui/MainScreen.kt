package com.sharafi.offlineassistant.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharafi.offlineassistant.stt.OfflineSttEngine.LanguageMode
import com.sharafi.offlineassistant.ui.theme.SharafiBackgroundDark
import com.sharafi.offlineassistant.ui.theme.SharafiCardDark
import com.sharafi.offlineassistant.ui.theme.SharafiCyanPrimary
import com.sharafi.offlineassistant.ui.theme.SharafiSurfaceDark
import com.sharafi.offlineassistant.ui.theme.SharafiTextPrimary
import com.sharafi.offlineassistant.ui.theme.SharafiTextSecondary
import com.sharafi.offlineassistant.ui.theme.SharafiTurquoiseSecondary

data class ExecutionLogEntry(
    val timestamp: String,
    val recognizedSpeech: String,
    val executionSummary: String,
    val isSuccess: Boolean
)

/**
 * MANDATE 4: MainScreen in Jetpack Compose, Material 3, Dark Theme with Cyan/Turquoise accents.
 * Features a large pulsing microphone button, live listening animation, recognized speech display,
 * language switcher (AR / EN), and compound command execution status log.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    isListening: Boolean,
    activeLanguage: LanguageMode,
    partialSpeech: String,
    finalSpeech: String,
    executionStatus: String,
    logs: List<ExecutionLogEntry>,
    onToggleListening: () -> Unit,
    onSwitchLanguage: (LanguageMode) -> Unit,
    onManualCommandSubmit: (String) -> Unit
) {
    var manualInput by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.24f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val outerPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.48f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerPulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SharafiBackgroundDark, SharafiSurfaceDark)
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header & Offline Badge + Language Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SHARAFI AI",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SharafiCyanPrimary,
                    letterSpacing = 1.5.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SharafiTurquoiseSecondary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "100% OFFLINE • VOSK + SHERPA-ONNX",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = SharafiTextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeLanguage == LanguageMode.ARABIC,
                    onClick = { onSwitchLanguage(LanguageMode.ARABIC) },
                    label = { Text("العربية") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SharafiCyanPrimary,
                        selectedLabelColor = SharafiBackgroundDark
                    )
                )
                FilterChip(
                    selected = activeLanguage == LanguageMode.ENGLISH,
                    onClick = { onSwitchLanguage(LanguageMode.ENGLISH) },
                    label = { Text("EN") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SharafiCyanPrimary,
                        selectedLabelColor = SharafiBackgroundDark
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Large Pulsing Microphone Button with Live Listening Animation
        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .scale(outerPulseScale)
                        .clip(CircleShape)
                        .background(SharafiCyanPrimary.copy(alpha = 0.12f))
                )
                Box(
                    modifier = Modifier
                        .size(136.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(SharafiTurquoiseSecondary.copy(alpha = 0.22f))
                )
            }

            Surface(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .border(
                        width = 3.dp,
                        color = if (isListening) SharafiTurquoiseSecondary else SharafiCyanPrimary,
                        shape = CircleShape
                    )
                    .clickable { onToggleListening() },
                color = if (isListening) SharafiCyanPrimary else SharafiCardDark,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = "Microphone Button",
                        tint = if (isListening) SharafiBackgroundDark else SharafiCyanPrimary,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isListening) "LISTENING..." else "TAP TO SPEAK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isListening) SharafiBackgroundDark else SharafiTextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Live Recognized Speech & Execution Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SharafiCyanPrimary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SharafiCardDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "RECOGNIZED SPEECH",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SharafiCyanPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        partialSpeech.isNotBlank() -> partialSpeech
                        finalSpeech.isNotBlank() -> finalSpeech
                        activeLanguage == LanguageMode.ARABIC -> "قل مثلاً: «شغل الكشاف وقولي كم نسبة البطارية»"
                        else -> "Say e.g.: "Turn on the flashlight and tell me the battery""
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (partialSpeech.isNotBlank()) SharafiTurquoiseSecondary else SharafiTextPrimary
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "EXECUTION STATUS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SharafiTurquoiseSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = executionStatus,
                    fontSize = 14.sp,
                    color = SharafiTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Compound Command Chips for One-Tap Testing
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sampleCommands = if (activeLanguage == LanguageMode.ARABIC) {
                listOf(
                    "شغل الكشاف وقولي كم البطارية",
                    "ارفع الصوت وافتح الكاميرا",
                    "اضبط مؤقت 5 دقائق"
                )
            } else {
                listOf(
                    "Turn on the flashlight and tell me the battery",
                    "Volume up and open camera",
                    "Set timer for 5 minutes"
                )
            }
            sampleCommands.forEach { sample ->
                AssistChip(
                    onClick = { onManualCommandSubmit(sample) },
                    label = { Text(sample, fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = SharafiCyanPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = SharafiCardDark,
                        labelColor = SharafiTextPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Direct Text Command Testing Input
        OutlinedTextField(
            value = manualInput,
            onValueChange = { manualInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    if (activeLanguage == LanguageMode.ARABIC) "اختبر أمراً مركباً كتابياً..."
                    else "Test a voice command or compound query..."
                )
            },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (manualInput.isNotBlank()) {
                            onManualCommandSubmit(manualInput)
                            manualInput = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Execute",
                        tint = SharafiCyanPrimary
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SharafiCyanPrimary,
                unfocusedBorderColor = SharafiCyanPrimary.copy(alpha = 0.35f),
                focusedTextColor = SharafiTextPrimary,
                unfocusedTextColor = SharafiTextPrimary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Execution History Log
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SharafiCardDark.copy(alpha = 0.75f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "“${item.recognizedSpeech}”",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SharafiCyanPrimary
                            )
                            Text(
                                text = item.timestamp,
                                fontSize = 11.sp,
                                color = SharafiTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.executionSummary,
                            fontSize = 13.sp,
                            color = if (item.isSuccess) SharafiTurquoiseSecondary else Color(0xFFFF8A80)
                        )
                    }
                }
            }
        }
    }
}
