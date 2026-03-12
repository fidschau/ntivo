package io.ntivo.web.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Colors mapped from the existing CSS dev console
val NtivoBackground = Color(0xFF0F0F1A)
val NtivoPrimary = Color(0xFF7C7CFF)
val NtivoSurface = Color(0xFF1A1A2E)
val NtivoSurfaceVariant = Color(0xFF16213E)
val NtivoOnSurface = Color(0xFFE0E0E0)
val NtivoOnSurfaceVariant = Color(0xFFAAAAAA)
val NtivoError = Color(0xFFFF6B6B)
val NtivoSuccess = Color(0xFF51CF66)
val NtivoOutline = Color(0xFF333355)

private val NtivoDarkColorScheme = darkColorScheme(
    primary = NtivoPrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF9C9CFF),
    background = NtivoBackground,
    surface = NtivoSurface,
    surfaceVariant = NtivoSurfaceVariant,
    onBackground = NtivoOnSurface,
    onSurface = NtivoOnSurface,
    onSurfaceVariant = NtivoOnSurfaceVariant,
    error = NtivoError,
    outline = NtivoOutline,
)

private val NtivoTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
)

@Composable
fun NtivoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NtivoDarkColorScheme,
        typography = NtivoTypography,
        content = content
    )
}
