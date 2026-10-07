package com.jarvys.agent

internal const val ASSISTANT_MAX_READING_WIDTH_DP = 720f

internal fun assistantReadingWidthLimitDp(availableWidthDp: Float): Float =
    availableWidthDp.coerceAtLeast(0f).coerceAtMost(ASSISTANT_MAX_READING_WIDTH_DP)

internal fun colorContrastRatio(foregroundArgb: Long, backgroundArgb: Long): Double {
    fun luminance(color: Long): Double {
        fun component(shift: Int): Double {
            val srgb = ((color shr shift) and 0xFF).toDouble() / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * component(16) + 0.7152 * component(8) + 0.0722 * component(0)
    }
    val first = luminance(foregroundArgb)
    val second = luminance(backgroundArgb)
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05) / (darker + 0.05)
}
