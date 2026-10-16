package com.jarvys.agent.canvas

/** Memory comparison policy for the two benchmark phases. */
internal object ChatCanvasMemoryPolicy {
    /** Compose has no chat WebView renderer, so its comparison baseline is process PSS alone. */
    fun composeTotalPssKb(processPssKb: Int): Int = processPssKb

    /** Canvas memory is unknown when Android does not expose its WebView renderer PSS. */
    fun canvasTotalPssKb(processPssKb: Int, rendererPssKb: Int?): Int? =
        rendererPssKb?.let { processPssKb + it }

    fun canvasWithinCompose(
        composeProcessPssKb: Int,
        canvasProcessPssKb: Int,
        canvasRendererPssKb: Int?,
    ): Boolean? = canvasTotalPssKb(canvasProcessPssKb, canvasRendererPssKb)
        ?.let { it <= composeTotalPssKb(composeProcessPssKb) }
}
