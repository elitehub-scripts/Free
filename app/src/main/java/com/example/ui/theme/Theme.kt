package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class WiFiShareSemanticColors(
    val success: Color,
    val warning: Color,
    val cyanAccent: Color,
    val cobaltAccent: Color,
    val glassSurface: Color,
    val glassBorder: Color,
    val textMuted: Color
)

val LocalWiFiShareColors = staticCompositionLocalOf {
    WiFiShareSemanticColors(
        success = EmeraldOnline,
        warning = AmberWarning,
        cyanAccent = ElectricCyan,
        cobaltAccent = CobaltBlue,
        glassSurface = CardNavy,
        glassBorder = OutlineNavy,
        textMuted = TextMuted
    )
}

private val WiFiShareDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = DeepNavy,
    primaryContainer = CobaltContainer,
    onPrimaryContainer = TextPrimary,
    secondary = CobaltBlue,
    onSecondary = TextPrimary,
    secondaryContainer = ElevatedNavy,
    onSecondaryContainer = TextPrimary,
    tertiary = EmeraldOnline,
    onTertiary = DeepNavy,
    background = DeepNavy,
    onBackground = TextPrimary,
    surface = SlateNavy,
    onSurface = TextPrimary,
    surfaceVariant = CardNavy,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = DeepNavy,
    surfaceContainerLow = SlateNavy,
    surfaceContainer = CardNavy,
    surfaceContainerHigh = ElevatedNavy,
    surfaceContainerHighest = ElevatedNavy,
    outline = OutlineNavy,
    outlineVariant = OutlineNavy.copy(alpha = 0.55f),
    error = CoralError,
    onError = DeepNavy
)

private val WiFiShareLightColorScheme = lightColorScheme(
    primary = CobaltDark,
    onPrimary = LightSurface,
    primaryContainer = LightCard,
    onPrimaryContainer = LightText,
    secondary = CyanDeep,
    onSecondary = LightSurface,
    secondaryContainer = LightCard,
    onSecondaryContainer = LightText,
    tertiary = EmeraldOnline,
    onTertiary = DeepNavy,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightCard,
    onSurfaceVariant = TextMuted,
    outline = LightOutline,
    error = CoralError,
    onError = LightSurface
)

val WiFiShareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class AppThemeMode { DARK, LIGHT, SYSTEM }

@Composable
fun WiFiShareTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) WiFiShareDarkColorScheme else WiFiShareLightColorScheme
    val semantic = if (darkTheme) {
        WiFiShareSemanticColors(
            success = EmeraldOnline,
            warning = AmberWarning,
            cyanAccent = ElectricCyan,
            cobaltAccent = CobaltBlue,
            glassSurface = CardNavy,
            glassBorder = OutlineNavy,
            textMuted = TextMuted
        )
    } else {
        WiFiShareSemanticColors(
            success = Color(0xFF00A854),
            warning = Color(0xFFD98200),
            cyanAccent = CyanDeep,
            cobaltAccent = CobaltDark,
            glassSurface = LightCard,
            glassBorder = LightOutline,
            textMuted = TextMuted
        )
    }

    CompositionLocalProvider(LocalWiFiShareColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WiFiShareTypography,
            shapes = WiFiShareShapes,
            content = content
        )
    }
}
