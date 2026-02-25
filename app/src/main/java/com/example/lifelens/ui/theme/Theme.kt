package com.example.lifelens.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = UserBubble,
    onPrimaryContainer = OnUserBubble,
    secondary = Amber40,
    onSecondary = Color.White,
    secondaryContainer = Amber80,
    onSecondaryContainer = Color(0xFF3E2723),
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral80,
    onTertiaryContainer = Color(0xFF442B2D),
    background = Cream,
    onBackground = Color(0xFF1C1B1F),
    surface = WarmSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = AssistantBubble,
    onSurfaceVariant = OnAssistantBubble,
    error = ErrorRed,
    errorContainer = ErrorRedLight,
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF79747E)
)

private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal30,
    primaryContainer = UserBubbleDark,
    onPrimaryContainer = OnUserBubbleDark,
    secondary = Amber80,
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF5D4037),
    onSecondaryContainer = Amber80,
    tertiary = Coral80,
    background = CreamDark,
    onBackground = Color(0xFFE6E1E5),
    surface = WarmSurfaceDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = AssistantBubbleDark,
    onSurfaceVariant = OnAssistantBubbleDark,
    error = Color(0xFFFFB4AB)
)

@Composable
fun LifeLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
