package com.siams.kern_prototype.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Светлая navy-схема (§14.8). Основной фон экранов приложения.
private val KernLightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Blue10,
    onPrimaryContainer = BlueDark,
    secondary = Blue40,
    onSecondary = Color.White,
    background = GrayLight,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Gray10,
    onSurfaceVariant = Gray40,
    outline = Gray20,
    outlineVariant = Gray10,
    error = Red30,
    onError = Color.White,
    errorContainer = Red10,
    onErrorContainer = Red30,
)

// Тёмная схема для экрана Съёмки и Просмотра стола (§14.7).
private val KernDarkColors = darkColorScheme(
    primary = Blue40,
    onPrimary = Ink,
    primaryContainer = BlueDark,
    onPrimaryContainer = Color.White,
    secondary = Blue30,
    background = Ink,
    onBackground = Color.White,
    surface = Gray50,
    onSurface = Color.White,
    surfaceVariant = Gray50,
    onSurfaceVariant = Gray20,
    outline = Gray40,
    error = Red30,
    onError = Color.White,
    errorContainer = Red10,
)

/**
 * Тема приложения. По умолчанию светлая navy (§14). Экраны съёмки/просмотра
 * оборачиваются в [KernTheme] с `dark = true` (§14.7).
 */
@Composable
fun KernTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) KernDarkColors else KernLightColors,
        typography = KernTypography,
        content = content,
    )
}

/** Совместимость с исходным именем шаблона (используется в Preview/MainActivity). */
@Composable
fun KernprototypeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) = KernTheme(dark = darkTheme, content = content)
