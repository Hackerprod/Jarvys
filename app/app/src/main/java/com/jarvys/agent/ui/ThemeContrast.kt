package com.jarvys.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Preserve the identity color while making its rendered sRGB ink readable. */
fun readableThemeInk(
    identity: Color,
    background: Color,
    onSurface: Color,
    minimumContrast: Double = 4.7,
): Color {
    require(minimumContrast.isFinite() && minimumContrast in 1.0..21.0)
    val surface = Color(background.toArgb())
    require(surface.alpha == 1f) { "Pass the fully composited, opaque background" }
    val original = Color(identity.toArgb())
    if (renderedContrast(original, surface) >= minimumContrast) return identity
    val fallback = Color(onSurface.toArgb())
    if (renderedContrast(fallback, surface) < minimumContrast) return onSurface
    fun blend(fraction: Float) = Color(
        red = original.red + (fallback.red - original.red) * fraction,
        green = original.green + (fallback.green - original.green) * fraction,
        blue = original.blue + (fallback.blue - original.blue) * fraction,
        alpha = original.alpha + (fallback.alpha - original.alpha) * fraction,
    )
    var unreadable = 0f
    var readable = 1f
    repeat(24) {
        val middle = (unreadable + readable) / 2f
        if (renderedContrast(blend(middle), surface) >= minimumContrast) readable = middle
        else unreadable = middle
    }
    return blend(readable)
}

private fun renderedContrast(foreground: Color, background: Color): Double {
    val visible = Color(foreground.toArgb()).compositeOver(background)
    val foregroundLuminance = srgbLuminance(visible.toArgb())
    val backgroundLuminance = srgbLuminance(background.toArgb())
    return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
        (min(foregroundLuminance, backgroundLuminance) + 0.05)
}

private fun srgbLuminance(argb: Int): Double {
    fun linear(shift: Int): Double {
        val channel = ((argb ushr shift) and 255) / 255.0
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    return linear(16) * 0.2126 + linear(8) * 0.7152 + linear(0) * 0.0722
}
