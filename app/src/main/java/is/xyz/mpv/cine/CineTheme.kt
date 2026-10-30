package `is`.xyz.mpv.cine

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun buildCineColorScheme(
    themeMode: String = "dark",
    accentColor: Color = Color(0xFFD92B55)
) = when (themeMode) {
    "amoled" -> darkColorScheme(
        primary = accentColor,
        onPrimary = Color.White,
        background = Color.Black,
        onBackground = Color(0xFFF1F1F6),
        surface = Color(0xFF0A0A0A),
        onSurface = Color(0xFFF1F1F6),
        surfaceVariant = Color(0xFF141414),
        onSurfaceVariant = Color(0xFFA0A0B0),
        surfaceContainer = Color(0xFF000000),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF262626),
        outline = Color(0xFF333333)
    )
    "light" -> lightColorScheme(
        primary = accentColor,
        onPrimary = Color.White,
        background = Color(0xFFF8F9FA),
        onBackground = Color(0xFF111827),
        surface = Color.White,
        onSurface = Color(0xFF111827),
        surfaceVariant = Color(0xFFEDF0F5),
        onSurfaceVariant = Color(0xFF4B5563),
        surfaceContainer = Color(0xFFF3F4F6),
        surfaceContainerHigh = Color(0xFFE5E7EB),
        surfaceContainerHighest = Color(0xFFD1D5DB),
        outline = Color(0xFFE2E8F0)
    )
    else -> darkColorScheme( // Standard Dark
        primary = accentColor,
        onPrimary = Color.White,
        background = Color(0xFF0E0E17),
        onBackground = Color(0xFFF1F1F6),
        surface = Color(0xFF141422),
        onSurface = Color(0xFFF1F1F6),
        surfaceVariant = Color(0xFF1E1E2E),
        onSurfaceVariant = Color(0xFFA0A0C0),
        surfaceContainer = Color(0xFF161626),
        surfaceContainerHigh = Color(0xFF222236),
        surfaceContainerHighest = Color(0xFF2D2D44),
        outline = Color(0xFF383852)
    )
}

@Composable
fun CineVerseTheme(
    themeMode: String = "dark",
    accentColor: Color = Color(0xFFD92B55),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = buildCineColorScheme(themeMode, accentColor),
        content = content
    )
}
