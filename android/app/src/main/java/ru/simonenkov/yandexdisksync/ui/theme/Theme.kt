package ru.simonenkov.yandexdisksync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = YandexRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFECE8),
    onPrimaryContainer = Color(0xFF410002),
    secondary = YandexBlack,
    onSecondary = Color.White,
    background = Color(0xFFFBFBFD),
    surface = Color.White,
    onSurface = TextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = YandexRed,
    onPrimary = Color.White,
    secondary = Color(0xFFE0E0E0),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEEEEEE)
)

@Composable
fun YandexDiskSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
