package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class SafDocumentConnectorTest {
    private class Gateway : SafDocumentGateway {
        var selectedName = "picked.txt"
        var selectedMime = "text/plain"
        var selectedBytes = "hello".toByteArray()
        var createdName = ""
        var createdMime = ""
        val written = ByteArrayOutputStream()
        override fun open(types: Array<String>, token: CancellationToken): SafPickedDocument =
            SafPickedDocument(selectedName, selectedMime, ByteArrayInputStream(selectedBytes))
        override fun create(name: String, mimeType: String, token: CancellationToken): OutputStream {
            createdName = name
            createdMime = mimeType
            return written
        }
    }

    @Test fun pickedTextIsUntrustedBoundedAndExplicitlyTruncated() {
        val gateway = Gateway().apply { selectedBytes = ByteArray(SafDocumentPolicy.MAX_BYTES + 10) { 'x'.code.toByte() } }
        val definition = SafDocumentConnector.definition(gateway)
        val result = definition.runtime!!.invoke(SafDocumentConnector.PICK_DOCUMENT, JSONObject(), CancellationToken.uncancellable())
        assertTrue(result.getBoolean("untrusted_content"))
        assertEquals("document_read", result.getString("status"))
        assertTrue(result.getBoolean("truncated"))
        val text = result.getJSONArray("items").getJSONObject(0).getString("text")
        assertTrue(text.endsWith("[Truncated at 1048576 bytes.]"))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= SafDocumentPolicy.MAX_BYTES + 2048)
    }

    @Test fun rejectsBinaryAndPdfMimeTypes() {
        assertFalse(SafDocumentPolicy.isTextMime("application/pdf"))
        assertFalse(SafDocumentPolicy.isTextMime("application/octet-stream"))
        assertTrue(runCatching { SafDocumentPolicy.readText("application/pdf", ByteArrayInputStream(byteArrayOf())) }.isFailure)
        assertTrue(SafDocumentPolicy.isTextMime("application/json"))
        assertTrue(SafDocumentPolicy.isTextMime("text/csv"))
        assertTrue(SafDocumentPolicy.isTextMime("text/markdown"))
    }

    @Test fun createFileIsAWriteAndDoesNotWriteBeforeApproval() {
        val gateway = Gateway()
        val definition = SafDocumentConnector.definition(gateway)
        val op = definition.operations.single { it.name == SafDocumentConnector.CREATE_DOCUMENT }
        assertFalse(op.autonomyAllowed)
        val token = CancellationToken.uncancellable()
        val args = JSONObject().put("name", "notes.txt").put("mime", "text/plain").put("content", "approved content")
        val preparation = definition.runtime!!.prepareWrite(op.name, args, token)
        assertEquals("", gateway.written.toString())
        assertTrue(preparation.approval.lines.any { it.contains("approved content") })
        val result = definition.runtime.invokePrepared(op.name, preparation.executionArguments, preparation, token)
        assertEquals("notes.txt", gateway.createdName)
        assertEquals("text/plain", gateway.createdMime)
        assertEquals("approved content", gateway.written.toString("UTF-8"))
        assertEquals("document_created", result.getString("status"))
    }
}
