package com.jarvys.agent.modules

/** Pixel and transport budget matches the maximum decoded module-artifact card preview. */
object ModuleThumbnailBudget {
    const val DECODE_TARGET_WIDTH_PX = 640
    const val DECODE_TARGET_HEIGHT_PX = 360
    // decodeChatAttachmentPreview allows a 2x sampling margin for high-density cards.
    const val MAX_CARD_BITMAP_WIDTH_PX = DECODE_TARGET_WIDTH_PX * 2
    const val MAX_CARD_BITMAP_HEIGHT_PX = DECODE_TARGET_HEIGHT_PX * 2
    const val BYTES_PER_RGBA_PIXEL = 4
    const val MAX_ENCODED_BYTES = MAX_CARD_BITMAP_WIDTH_PX * MAX_CARD_BITMAP_HEIGHT_PX * BYTES_PER_RGBA_PIXEL
    const val MAX_BASE64_CHARS = ((MAX_ENCODED_BYTES + 2L) / 3L * 4L)
}
