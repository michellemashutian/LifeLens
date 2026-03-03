package com.example.lifelens.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lifelens.tool.SpeechSpeed

@Composable
fun PhotoScreen(
    bitmap: Bitmap?,
    currentAnswer: String,
    streamingAnswer: String,
    isProcessing: Boolean,
    questionText: String,
    onQuestionTextChange: (String) -> Unit,
    isListening: Boolean,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    onSubmit: () -> Unit,
    onHistory: () -> Unit,
    isSpeaking: Boolean = false,
    onSpeakClick: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    speechSpeed: SpeechSpeed = SpeechSpeed.SLOW,
    onSpeedChange: (SpeechSpeed) -> Unit = {}
) {
    val answerToShow = when {
        currentAnswer.isNotBlank() -> currentAnswer
        isProcessing && streamingAnswer.isNotBlank() -> streamingAnswer
        isProcessing -> "..."
        else -> ""
    }
    val showAnswer = answerToShow.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {

        // Full-screen image background
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.DarkGray)
            )
        }

        // Dim overlay when showing answer for better readability
        if (showAnswer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        }

        // History icon — top left
        IconButton(
            onClick = onHistory,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = "History",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp)
            )
        }

        // Question prompt — shown when no answer yet
        if (!showAnswer) {
            Text(
                text = "What question\ndo you have?",
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f
                    )
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .offset(y = (-60).dp)
            )
        }

        // Answer overlay — shown when answer is available
        if (showAnswer) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.92f)
                    .padding(bottom = 96.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                // Answer text
                Text(
                    text = answerToShow,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 36.dp)  // space for speaker icon
                        .verticalScroll(rememberScrollState())
                )

                // Speaker + speed controls — top-right corner of answer box
                Column(
                    modifier = Modifier.align(Alignment.TopEnd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = {
                            if (isSpeaking) onStopSpeaking()
                            else onSpeakClick(currentAnswer.ifBlank { answerToShow })
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (isSpeaking) "Stop" else "Read aloud",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Speed toggle S / N
                    SpeechSpeed.entries.forEach { speed ->
                        val selected = speechSpeed == speed
                        TextButton(
                            onClick = { onSpeedChange(speed) },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text(
                                text = speed.label.first().toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.45f),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // Bottom input bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = onQuestionTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type a question…",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.8f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                        cursorColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f)
                    ),
                    trailingIcon = if (questionText.isNotBlank() && !isProcessing) {
                        {
                            TextButton(onClick = onSubmit) {
                                Text("Ask", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else null
                )

                // Mic button — hold to talk
                FloatingActionButton(
                    onClick = {},
                    modifier = Modifier
                        .size(52.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown()
                                    onMicDown()
                                    waitForUpOrCancellation()
                                    onMicUp()
                                }
                            }
                        },
                    shape = CircleShape,
                    containerColor = if (isListening) Color.Red else Color.White.copy(alpha = 0.9f),
                    contentColor = if (isListening) Color.White else Color.DarkGray,
                    elevation = FloatingActionButtonDefaults.elevation(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Hold to talk",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = if (isListening) "Listening…" else "hold to talk",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f)
            )
        }

        // Processing indicator
        if (isProcessing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}
