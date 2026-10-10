package com.jarvys.agent.apkfactory

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public view APIs with synthetic text only; no Activity, service, or external editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 30, 35], application = android.app.Application::class)
class FactoryMessageEditorPrivacyCompatibilityTest {
    @Test fun privateReviewColumnDoesNotExportNestedTextButKeepsAccessibilityAvailable() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val column = PrivateReviewColumn(context)
        val nested = LinearLayout(context)
        val child = TextView(context).apply {
            text = "Synthetic recipient fixture@example.invalid\nSynthetic private body"
            contentDescription = "Synthetic accessible review text"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints("emailAddress")
            }
        }
        nested.addView(child); column.addView(nested)
        assertFalse(column.isSaveEnabled)
        assertFalse(column.isSaveFromParentEnabled)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, column.importantForAccessibility)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, column.importantForAccessibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, child.importantForAccessibility)
        val node = child.createAccessibilityNodeInfo()
        try {
            assertEquals(child.text.toString(), node.text.toString())
            assertEquals(child.contentDescription.toString(), node.contentDescription.toString())
        } finally { node.recycle() }
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, column.importantForAutofill)
            for (flags in listOf(0, View.AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)) {
                val structure = FactoryEditorRecordingViewStructure()
                column.dispatchProvideAutofillStructure(structure, flags)
                assertEquals(listOf("setAutofillId"), structure.calls)
                assertEquals(column.autofillId, structure.recordedId)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, column.importantForContentCapture)
        }
    }
}
