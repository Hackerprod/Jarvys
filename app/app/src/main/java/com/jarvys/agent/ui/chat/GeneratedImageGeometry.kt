package com.jarvys.agent.ui.chat

internal data class GeneratedImageTransform(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

internal data class GeneratedImageGeometry(val viewportWidth: Float, val viewportHeight: Float,
    val imageWidth: Float, val imageHeight: Float) {
    private val fit = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val fittedWidth = imageWidth * fit
    val fittedHeight = imageHeight * fit
    fun clamp(transform: GeneratedImageTransform): GeneratedImageTransform {
        val scale = transform.scale.coerceIn(1f, 5f)
        if (scale == 1f) return GeneratedImageTransform()
        val maxX = maxOf(0f, (fittedWidth * scale - viewportWidth) / 2f)
        val maxY = maxOf(0f, (fittedHeight * scale - viewportHeight) / 2f)
        return GeneratedImageTransform(scale, transform.panX.coerceIn(-maxX, maxX), transform.panY.coerceIn(-maxY, maxY))
    }
    fun gesture(previous: GeneratedImageTransform, zoom: Float, deltaX: Float, deltaY: Float,
        centroidX: Float, centroidY: Float): GeneratedImageTransform {
        val scale = (previous.scale * zoom).coerceIn(1f, 5f)
        val ratio = scale / previous.scale
        return clamp(GeneratedImageTransform(scale,
            previous.panX * ratio + (centroidX - viewportWidth / 2f) * (1f - ratio) + deltaX,
            previous.panY * ratio + (centroidY - viewportHeight / 2f) * (1f - ratio) + deltaY))
    }
}
