package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFooterPresentationTest {
    @Test fun speechTextOmitsFencedAndInlineCode() {
        val source = "Read this `inline snippet`.\n```kotlin\nprintln(1)\n```\nKeep this sentence."
        assertEquals("Read this . Keep this sentence.", assistantSpeechText(source))
    }

    @Test fun speechTextKeepsVisibleLinkLabelAndRemovesMarkdownDecorations() {
        assertEquals("See the guide table cell", assistantSpeechText("## See [the guide](https://example.test)\n| table | cell |"))
    }

    @Test fun anUnclosedFenceSuppressesCodeThroughTheEndOfTheMessage() {
        assertEquals("Read this first", assistantSpeechText("Read this first\n~~~text\nnot spoken\nstill not spoken"))
    }

    @Test fun nestedLinkLabelsAreSpokenButDestinationsAreNot() {
        val markdown = "> 1. Read [the [deep guide](https://inner.test/a)](https://outer.test/path_(part))"
        assertEquals("Read the deep guide", assistantSpeechText(markdown))
    }

    @Test fun indentedBulletsAndOrderedListMarkersDoNotReachSpeech() {
        assertEquals("First item Second item Third item", assistantSpeechText("  - First item\n      2) Second item\n\t3. Third item"))
    }

    @Test fun unmatchedAsterisksRemainLiteralWhileMatchedEmphasisMarkersAreRemoved() {
        assertEquals("Compute 2 * 3; keep this literal * star. Bold and soft words.",
            assistantSpeechText("Compute 2 * 3; keep this literal * star. **Bold** and _soft_ words."))
    }

    @Test fun imagesSpeakTheirAltDescriptionAndNeverTheirTarget() {
        assertEquals("Diagram network flow follows", assistantSpeechText("Diagram ![network flow](https://image.test/map.png) follows"))
    }
}
