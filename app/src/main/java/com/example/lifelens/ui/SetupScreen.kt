package com.example.lifelens.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    headline: String,
    detail: String,
    progress: Int?,
    errorText: String?,
    running: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium)

                    Spacer(Modifier.height(14.dp))
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress / 100f })
                        Spacer(Modifier.height(8.dp))
                        Text("$progress%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator()
                    }

                    if (errorText != null) {
                        Spacer(Modifier.height(14.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        Text("Error details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(errorText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRetry,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Retry") }

                            OutlinedButton(
                                onClick = onBack,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Back") }
                        }
                    }
                }
            }
        }
    }
}
