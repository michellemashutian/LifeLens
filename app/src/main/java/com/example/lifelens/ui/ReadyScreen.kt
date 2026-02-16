package com.example.lifelens.ui

import android.graphics.BitmapFactory
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lifelens.tool.Audience
import com.example.lifelens.ui.components.ChatBubble
import com.example.lifelens.ui.components.OverlayHint
import com.example.lifelens.ui.components.SystemPill

@Composable
fun ReadyScreen(
    previewView: PreviewView,
    cameraGranted: Boolean,
    cameraReady: Boolean,
    uploadedImagePath: String?,
    onRequestCamera: () -> Unit,
    onBindCamera: () -> Unit,
    audience: Audience,
    onAudienceChange: (Audience) -> Unit,
    onUpload: () -> Unit,
    onCapture: () -> Unit,
    headline: String,
    detail: String,
    questionText: String,
    onQuestionTextChange: (String) -> Unit,
    isProcessing: Boolean,
    streamingAnswer: String,
    onAskSubmit: () -> Unit,
    onQuickTest: () -> Unit
) {
    val listState = rememberLazyListState()

    val looksLikeUserQuestion = remember(detail) {
        val d = detail.trim()
        d.isNotBlank() &&
                d.length >= 4 &&
                !d.startsWith("Preparing", ignoreCase = true) &&
                !d.startsWith("Loading", ignoreCase = true) &&
                !d.startsWith("Camera", ignoreCase = true) &&
                !d.startsWith("Try:", ignoreCase = true) &&
                !d.startsWith("Downloading", ignoreCase = true) &&
                !d.startsWith("Initializing", ignoreCase = true)
    }
    val lastSubmittedQuestion = if (looksLikeUserQuestion) detail.trim() else ""

    data class ChatMsg(val role: String, val text: String)

    val chatMessages = remember(headline, detail, streamingAnswer, isProcessing) {
        buildList {
            val h = headline.trim()
            val d = detail.trim()
            val isError = h.contains("failed", true) || h.contains("error", true)
            val isThinking = isProcessing || h.contains("thinking", true) || h.contains("loading", true)

            if (isError) add(ChatMsg("system", if (d.isNotBlank()) "$h — $d" else h))
            else if (isThinking) add(ChatMsg("system", "Thinking…"))

            if (lastSubmittedQuestion.isNotBlank()) add(ChatMsg("user", lastSubmittedQuestion))
            if (streamingAnswer.isNotBlank()) add(ChatMsg("assistant", streamingAnswer))
            else if (isThinking && lastSubmittedQuestion.isNotBlank()) add(ChatMsg("assistant", "…"))
        }
    }

    LaunchedEffect(chatMessages.size, streamingAnswer.length) {
        if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1)
    }

    val hasImage = uploadedImagePath != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LifeLens", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = audience == Audience.ELDERLY,
                onClick = { onAudienceChange(Audience.ELDERLY) },
                label = { Text("Elderly") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = audience == Audience.CHILD,
                onClick = { onAudienceChange(Audience.CHILD) },
                label = { Text("Child") }
            )
        }

        Spacer(Modifier.height(10.dp))

        // Preview: show uploaded/default image if available, else camera
        Card(shape = RoundedCornerShape(20.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
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
                            subtitle = "Try: Emulator → Settings → Camera → Webcam0",
                            primary = "Retry",
                            onPrimary = onBindCamera
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCapture,
                enabled = cameraGranted && cameraReady && !isProcessing,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Capture") }

            OutlinedButton(
                onClick = onUpload,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Upload") }
        }

        Spacer(Modifier.height(6.dp))

        // Small Quick Test row
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) { Text("Quick Test") }
        }

        Spacer(Modifier.height(10.dp))

        // Chat
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                if (chatMessages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                    ) {
                        items(chatMessages) { msg ->
                            when (msg.role) {
                                "system" -> SystemPill(text = msg.text)
                                "user" -> ChatBubble(text = msg.text, isUser = true)
                                else -> ChatBubble(text = msg.text, isUser = false)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Input bar
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = onQuestionTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What is this? Is it safe?") },
                    singleLine = true,
                    enabled = !isProcessing
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onAskSubmit,
                    enabled = questionText.isNotBlank() && !isProcessing && (hasImage || (cameraGranted && cameraReady)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(52.dp)
                ) { Text(if (isProcessing) "..." else "Ask") }
            }

            if (isProcessing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
