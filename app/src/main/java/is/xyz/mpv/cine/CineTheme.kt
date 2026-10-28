package `is`.xyz.mpv.cine

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CineColors = darkColorScheme(
    primary = Color(0xFFFF4D6D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5C1424),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFF9B6BFF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF33265C),
    onSecondaryContainer = Color(0xFFE7DEFF),
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFF1F1F6),
    surface = Color(0xFF12121C),
    onSurface = Color(0xFFF1F1F6),
    surfaceVariant = Color(0xFF1E1E2E),
    onSurfaceVariant = Color(0xFFB0B0C0),
    surfaceContainer = Color(0xFF161624),
    surfaceContainerHigh = Color(0xFF1E1E2E),
    surfaceContainerHighest = Color(0xFF2A2A3C),
    outline = Color(0xFF3A3A4E),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF2A0009),
)

@Composable
fun CineVerseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CineColors, content = content)
}
