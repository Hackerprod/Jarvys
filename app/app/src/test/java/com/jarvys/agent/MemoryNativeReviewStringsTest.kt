package com.jarvys.agent

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ApprovalSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryNativeReviewStringsTest {
    private fun localized(language: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    @Test fun shareMetadataLocalizesWithoutChangingExactContentOrOriginalSummaryInEnglishAndSpanish() {
        val content = "Selected memory scope: user text\nExact state: keep this\n" +
            "Save note\nSource conversation: literal\nRevision: 7\nSHA-256: literal\n" +
            "Android-selected destination: content://literal/%1\$s\nExact content:\nñ"
        val path = "Source conversation: literal.md"
        val source = "session-%1\$s-ñ"
        val hash = NativeActionPolicy.binding(content)
        val summary = ApprovalSummary(NativeActionPolicy.Operation.MEMORY_SHARE_PERSONAL.id, listOf(
            "Selected memory scope: scope-hash", "Exact state: state-hash",
            "Share immutable PERSONAL snapshot", path, "Destination: shared PERSONAL projection",
            "Source conversation: $source", "Revision: 73", "SHA-256: $hash", "Exact content:", content,
        ), allowAlwaysAvailable = false)
        val original = summary.lines.toList()
        val binding = NativeActionPolicy.binding(*summary.lines.toTypedArray())
        for (language in listOf("en", "es")) {
            val context = localized(language)
            val displayed = nativeMemoryReviewLines(context, summary)
            assertNotSame(summary.lines, displayed)
            assertEquals(context.getString(R.string.memory_native_selected_scope, "scope-hash"), displayed[0])
            assertEquals(context.getString(R.string.memory_native_exact_state, "state-hash"), displayed[1])
            assertEquals(context.getString(R.string.memory_native_share_snapshot), displayed[2])
            assertArrayEquals(path.toByteArray(), displayed[3].toByteArray())
            assertEquals(context.getString(R.string.memory_native_share_destination), displayed[4])
            assertEquals(context.getString(R.string.memory_native_source_conversation, source), displayed[5])
            assertEquals(context.getString(R.string.memory_native_source_revision, "73"), displayed[6])
            assertEquals(context.getString(R.string.memory_native_source_hash, hash), displayed[7])
            assertEquals(context.getString(R.string.memory_native_exact_content), displayed[8])
            assertArrayEquals(content.toByteArray(), displayed[9].toByteArray())
            assertEquals(hash, NativeActionPolicy.binding(displayed[9]))
            assertEquals(original, summary.lines)
            assertEquals(binding, NativeActionPolicy.binding(*summary.lines.toTypedArray()))
            assertFalse(summary.allowAlwaysAvailable)
            assertEquals(if (language == "es") "Permitir una vez" else "Allow once",
                context.getString(R.string.memory_native_allow_once))
        }
    }

    @Test fun exportUriAndNoteBodyStayByteIdenticalInEnglishAndSpanish() {
        val uri = "content://example/document/Source%20conversation%3A%20ñ?literal=%1\$s"
        val export = ApprovalSummary(NativeActionPolicy.Operation.MEMORY_EXPORT.id, listOf(
            "Selected memory scope: scope", "Exact state: state", "Export selected memory",
            "Include journal: true", "Android-selected destination: $uri",
        ))
        val body = "Save note\nExact state: literal\nAndroid-selected destination: $uri"
        val save = ApprovalSummary(NativeActionPolicy.Operation.MEMORY_MODIFY.id, listOf(
            "Selected memory scope: scope", "Exact state: state", "Save note", "note.md", body, "1", "false", "session",
        ))
        val originalExport = export.lines.toList()
        val originalSave = save.lines.toList()
        for (language in listOf("en", "es")) {
            val context = localized(language)
            val displayed = nativeMemoryReviewLines(context, export)
            val prefix = context.getString(R.string.memory_native_export_destination, "")
            assertArrayEquals(uri.toByteArray(), displayed[4].removePrefix(prefix).toByteArray())
            assertEquals(context.getString(R.string.memory_native_export_include_journal, "true"), displayed[3])
            assertArrayEquals(body.toByteArray(), nativeMemoryReviewLines(context, save)[4].toByteArray())
            assertEquals(originalExport, export.lines)
            assertEquals(originalSave, save.lines)
        }
    }

    @Test fun chatRestoreVariantsLocalizeInEnglishAndSpanish() {
        val variants = listOf(
            "Undo exact chat memory revision" to R.string.memory_native_undo_chat_revision,
            "Undo exact reflection group" to R.string.memory_native_undo_reflection_group,
        )
        for (language in listOf("en", "es")) for ((label, resource) in variants) {
            val context = localized(language)
            val summary = ApprovalSummary(NativeActionPolicy.Operation.MEMORY_RESTORE.id, listOf(
                "Selected memory scope: scope", "Exact state: state", label, "exact-id", "session",
            ))
            val displayed = nativeMemoryReviewLines(context, summary)
            assertEquals(context.getString(resource), displayed[2])
            assertEquals(summary.lines.drop(3), displayed.drop(3))
            assertEquals(label, summary.lines[2])
        }
    }
}
