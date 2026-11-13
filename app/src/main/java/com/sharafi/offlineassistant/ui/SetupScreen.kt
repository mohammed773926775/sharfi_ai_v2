package com.sharafi.offlineassistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharafi.offlineassistant.ui.theme.SharafiBackgroundDark
import com.sharafi.offlineassistant.ui.theme.SharafiCardDark
import com.sharafi.offlineassistant.ui.theme.SharafiCyanPrimary
import com.sharafi.offlineassistant.ui.theme.SharafiErrorRed
import com.sharafi.offlineassistant.ui.theme.SharafiTextPrimary
import com.sharafi.offlineassistant.ui.theme.SharafiTextSecondary
import com.sharafi.offlineassistant.ui.theme.SharafiTurquoiseSecondary

data class SetupUiState(
    val isDownloading: Boolean = false,
    val voskDownloaded: Boolean = false,
    val sherpaDownloaded: Boolean = false,
    val overallProgress: Float = 0f,
    val statusText: String = "Checking offline speech models...",
    val errorText: String? = null
)

/**
 * MANDATE 4: SetupScreen that checks if Vosk STT & Sherpa-ONNX TTS models are downloaded;
 * if not, displays a live progress bar and downloads/extracts them into internal storage.
 */
@Composable
fun SetupScreen(
    uiState: SetupUiState,
    onRetryDownload: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.overallProgress.coerceIn(0f, 1f),
        label = "setupProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SharafiBackgroundDark, SharafiCardDark)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(SharafiCyanPrimary.copy(alpha = 0.12f))
                    .border(2.dp, SharafiCyanPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Offline Models Setup",
                    tint = SharafiCyanPrimary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "SHARAFI AI",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = SharafiCyanPrimary,
                letterSpacing = 2.sp
            )

            Text(
                text = "Offline Neural Voice Assistant Setup",
                fontSize = 15.sp,
                color = SharafiTextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SharafiCyanPrimary.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SharafiCardDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    ModelCheckRow(
                        title = "Vosk Offline STT (Arabic + English)",
                        subtitle = "vosk-model-small-ar-0.3 & vosk-model-small-en-us-0.15",
                        isReady = uiState.voskDownloaded
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    ModelCheckRow(
                        title = "Sherpa-ONNX Offline TTS (Arabic + English)",
                        subtitle = "vits-piper-ar & vits-piper-en Neural Voices",
                        isReady = uiState.sherpaDownloaded
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        color = SharafiTurquoiseSecondary,
                        trackColor = SharafiBackgroundDark
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.statusText,
                            fontSize = 13.sp,
                            color = SharafiTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SharafiTurquoiseSecondary
                        )
                    }

                    if (uiState.errorText != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SharafiErrorRed.copy(alpha = 0.15f))
                                .padding(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = SharafiErrorRed
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.errorText,
                                fontSize = 12.sp,
                                color = SharafiErrorRed
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = onRetryDownload,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SharafiCyanPrimary,
                                contentColor = SharafiBackgroundDark
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Model Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Models are downloaded once on first launch and stored in internal storage for 100% offline execution.",
                fontSize = 12.sp,
                color = SharafiTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModelCheckRow(
    title: String,
    subtitle: String,
    isReady: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isReady) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Ready",
                tint = SharafiTurquoiseSecondary,
                modifier = Modifier.size(24.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = SharafiCyanPrimary,
                strokeWidth = 2.5.dp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SharafiTextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = SharafiTextSecondary
            )
        }
    }
}
