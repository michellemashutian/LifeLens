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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onHome: () -> Unit,
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
        isProcessing -> "Analyzing..."
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

        // Dim overlay when showing answer
        if (showAnswer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        }

        // Top bar: Home (left) + History (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onHome,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Home",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = onHistory,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = "History",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Answer overlay
        if (showAnswer) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.92f)
                    .padding(bottom = 100.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = answerToShow,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 36.dp)
                        .verticalScroll(rememberScrollState())
                )

                // Speaker + speed controls — top-right of answer box
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
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Text field
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
                    )
                )

                // Ask button — prominent, always visible
                Button(
                    onClick = onSubmit,
                    enabled = questionText.isNotBlank() && !isProcessing,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.15f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Ask",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Ask", fontWeight = FontWeight.SemiBold)
                }

                // Mic button — hold to talk
                FloatingActionButton(
                    onClick = {},
                    modifier = Modifier
                        .size(48.dp)
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
                        modifier = Modifier.size(22.dp)
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
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}
