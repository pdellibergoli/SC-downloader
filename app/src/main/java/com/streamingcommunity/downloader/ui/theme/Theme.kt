package com.streamingcommunity.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF090D16)
val DarkSurface = Color(0xFF111827)
val DarkSurfaceVariant = Color(0xFF1E293B)
val AccentRed = Color(0xFFE50914)
val AccentCyan = Color(0xFF38BDF8)
val AccentEmerald = Color(0xFF10B981)
val AccentAmber = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val SCDarkColorScheme = darkColorScheme(
    primary = AccentRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF450A0A),
    onPrimaryContainer = Color(0xFFFECACA),
    secondary = AccentCyan,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = AccentEmerald,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary
)

@Composable
fun SCDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SCDarkColorScheme,
        content = content
    )
}
