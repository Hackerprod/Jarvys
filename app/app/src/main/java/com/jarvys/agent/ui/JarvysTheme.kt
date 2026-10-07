package com.jarvys.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.jarvys.agent.JarvysPalette
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.ui.motion.JarvysMotionProvider

@Composable
fun JarvysOwnTheme(mode: JarvysThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        JarvysThemeMode.SYSTEM -> isSystemInDarkTheme()
        JarvysThemeMode.LIGHT -> false
        JarvysThemeMode.DARK -> true
    }
    val colors = if (dark) darkColorScheme(
        primary = JarvysPalette.GlassDark,
        onPrimary = JarvysPalette.CanvasDark,
        primaryContainer = JarvysPalette.RaisedDark,
        onPrimaryContainer = JarvysPalette.InkDark,
        secondary = JarvysPalette.BrassDark,
        onSecondary = JarvysPalette.CanvasDark,
        secondaryContainer = JarvysPalette.RaisedDark,
        onSecondaryContainer = JarvysPalette.InkDark,
        tertiary = JarvysPalette.BrassDark,
        onTertiary = JarvysPalette.CanvasDark,
        tertiaryContainer = JarvysPalette.RaisedDark,
        onTertiaryContainer = JarvysPalette.InkDark,
        error = JarvysPalette.ClayDark,
        onError = JarvysPalette.CanvasDark,
        errorContainer = JarvysPalette.RaisedDark,
        onErrorContainer = JarvysPalette.ClayDark,
        background = JarvysPalette.CanvasDark,
        onBackground = JarvysPalette.InkDark,
        surface = JarvysPalette.SurfaceDark,
        onSurface = JarvysPalette.InkDark,
        surfaceVariant = JarvysPalette.RaisedDark,
        onSurfaceVariant = JarvysPalette.SecondaryInkDark,
        outline = JarvysPalette.SecondaryInkDark,
        outlineVariant = JarvysPalette.RuleDark,
        inverseSurface = JarvysPalette.InkDark,
        inverseOnSurface = JarvysPalette.CanvasDark,
        inversePrimary = JarvysPalette.GlassLight,
        surfaceTint = JarvysPalette.GlassDark,
    ) else lightColorScheme(
        primary = JarvysPalette.GlassLight,
        onPrimary = JarvysPalette.SurfaceLight,
        primaryContainer = JarvysPalette.RaisedLight,
        onPrimaryContainer = JarvysPalette.InkLight,
        secondary = JarvysPalette.BrassLight,
        onSecondary = JarvysPalette.SurfaceLight,
        secondaryContainer = JarvysPalette.RaisedLight,
        onSecondaryContainer = JarvysPalette.InkLight,
        tertiary = JarvysPalette.BrassLight,
        onTertiary = JarvysPalette.SurfaceLight,
        tertiaryContainer = JarvysPalette.RaisedLight,
        onTertiaryContainer = JarvysPalette.InkLight,
        error = JarvysPalette.ClayLight,
        onError = JarvysPalette.SurfaceLight,
        errorContainer = JarvysPalette.RaisedLight,
        onErrorContainer = JarvysPalette.ClayLight,
        background = JarvysPalette.CanvasLight,
        onBackground = JarvysPalette.InkLight,
        surface = JarvysPalette.SurfaceLight,
        onSurface = JarvysPalette.InkLight,
        surfaceVariant = JarvysPalette.RaisedLight,
        onSurfaceVariant = JarvysPalette.SecondaryInkLight,
        outline = JarvysPalette.SecondaryInkLight,
        outlineVariant = JarvysPalette.RuleLight,
        inverseSurface = JarvysPalette.InkLight,
        inverseOnSurface = JarvysPalette.SurfaceLight,
        inversePrimary = JarvysPalette.GlassDark,
        surfaceTint = JarvysPalette.GlassLight,
    )
    MaterialTheme(colorScheme = colors) {
        JarvysMotionProvider(content)
    }
}
