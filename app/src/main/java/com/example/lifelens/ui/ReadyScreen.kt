package com.example.lifelens.ui

import android.graphics.BitmapFactory
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lifelens.tool.SpeechSpeed
import com.example.lifelens.ui.components.ChatBubble
import com.example.lifelens.ui.components.OverlayHint
import com.example.lifelens.ui.components.SystemPill

data class ChatMsg(val role: String, val text: String)

@Composable
fun ReadyScreen(
    previewView: PreviewView,
    cameraGranted: Boolean,
    cameraReady: Boolean,
    uploadedImagePath: String?,
    onRequestCamera: () -> Unit,
    onBindCamera: () -> Unit,
    onUpload: () -> Unit,
    onCapture: () -> Unit,
    chatHistory: List<ChatMsg>,
    questionText: String,
    onQuestionTextChange: (String) -> Unit,
    isProcessing: Boolean,
    streamingAnswer: String,
    onAskSubmit: () -> Unit,
    onQuickTest: () -> Unit,
    isSpeaking: Boolean = false,
    onSpeakClick: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    speechSpeed: SpeechSpeed = SpeechSpeed.SLOW,
    onSpeedChange: (SpeechSpeed) -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Build display list: full history + current streaming indicator
    val displayMessages = remember(chatHistory.size, streamingAnswer, isProcessing) {
        buildList {
            addAll(chatHistory)
            // If currently streaming, show partial answer at the bottom
            if (isProcessing && streamingAnswer.isNotBlank()) {
                add(ChatMsg("assistant", streamingAnswer))
            } else if (isProcessing && streamingAnswer.isBlank()) {
                add(ChatMsg("assistant", "..."))
            }
        }
    }

    LaunchedEffect(displayMessages.size, streamingAnswer.length) {
        if (displayMessages.isNotEmpty()) listState.animateScrollToItem(displayMessages.size - 1)
    }

    val hasImage = uploadedImagePath != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Camera preview card
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val bitmap = remember(uploadedImagePath) {
                    uploadedImagePath?.let { path ->
                        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    }
                }

                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Loaded image",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                    if (!cameraGranted) {
                        OverlayHint(
                            title = "Camera permission needed",
                            subtitle = "You can still upload or use Quick Test.",
                            primary = "Grant",
                            onPrimary = onRequestCamera
                        )
                    } else if (!cameraReady) {
                        OverlayHint(
                            title = "Camera not ready",
                            subtitle = "Try: Emulator > Settings > Camera > Webcam0",
                            primary = "Retry",
                            onPrimary = onBindCamera
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons with icons
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCapture,
                enabled = cameraGranted && cameraReady && !isProcessing,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Capture", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = onUpload,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upload", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Quick Test row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "No photo? Try a demo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onQuickTest,
                enabled = !isProcessing,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Quick Test")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Chat area
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                if (displayMessages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Ask about what you see",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Capture or upload a photo, then ask a question.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                    ) {
                        items(displayMessages) { msg ->
                            when (msg.role) {
                                "system" -> SystemPill(text = msg.text)
                                "user" -> ChatBubble(text = msg.text, isUser = true)
                                else -> ChatBubble(
                                    text = msg.text,
                                    isUser = false,
                                    showSpeaker = msg.text != "...",
                                    isSpeaking = isSpeaking,
                                    onSpeakClick = {
                                        if (isSpeaking) onStopSpeaking() else onSpeakClick(msg.text)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Speech speed selector: Slow | Normal
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Read speed:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            SpeechSpeed.entries.forEach { speed ->
                val selected = speechSpeed == speed
                TextButton(
                    onClick = { onSpeedChange(speed) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        text = speed.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Input bar
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = onQuestionTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What is this? Is it safe?") },
                    singleLine = true,
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onAskSubmit,
                    enabled = questionText.isNotBlank() && !isProcessing && (hasImage || (cameraGranted && cameraReady)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask", modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ask", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            if (isProcessing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
        }
    }
}
