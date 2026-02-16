package com.example.lifelens.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun IntroScreen(
    title: String,
    subtitle: String,
    primaryText: String,
    onPrimary: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)

            Spacer(Modifier.height(10.dp))

            Text(subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(primaryText)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Tip: The first run downloads the on-device model.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
