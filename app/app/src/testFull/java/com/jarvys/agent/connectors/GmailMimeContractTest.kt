package com.jarvys.agent.connectors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class GmailMimeContractTest {
    private fun part(mime: String, value: ByteArray, charset: String = "UTF-8") = JSONObject().put("mimeType", mime)
        .put("headers", JSONArray().put(JSONObject().put("name", "Content-Type").put("value", "$mime; charset=\"$charset\"")))
        .put("body", JSONObject().put("size", value.size).put("data", GoogleOAuthProtocol.base64Url(value)))
    private fun row(payload: JSONObject) = GmailContent.messageRow(JSONObject().put("id", "m1").put("payload", payload))

    @Test fun utf8SubjectFoldsIntoValidEncodedWordsWithoutSplittingEmoji() {
        val subject = "Resumen 🚀 café 日本語 ".repeat(20)
        val raw = GmailContent.rawMessage(EmailDraft("a@example.com", subject, "Línea uno\n次の行 🚀", "", ""))
        val mime = String(GoogleOAuthProtocol.base64UrlDecode(raw), StandardCharsets.UTF_8)
        val headerBlock = mime.substringBefore("\r\n\r\n")
        assertTrue(headerBlock.lines().all { it.toByteArray().size <= 998 })
        val words = Regex("=\\?UTF-8\\?B\\?([^?]+)\\?=").findAll(headerBlock).toList()
        assertTrue(words.size > 2)
        assertTrue(words.all { it.value.length <= 75 })
        val decoded = words.joinToString("") { String(GmailContent.decode(it.groupValues[1].replace('+', '-').replace('/', '_'), 60), StandardCharsets.UTF_8) }
        assertEquals(subject, decoded)
        assertTrue(headerBlock.contains("Content-Transfer-Encoding: base64"))
        val body = mime.substringAfter("\r\n\r\n").replace("\r\n", "")
        assertEquals("Línea uno\r\n次の行 🚀", String(GmailContent.decode(body.replace('+', '-').replace('/', '_'), 100), StandardCharsets.UTF_8))
    }

    @Test fun longAsciiSubjectsAndRecipientsRespectRfcLineLengths() {
        val subject = "S".repeat(500)
        val addresses = (1..20).joinToString(",") { "recipient$it@long-example-domain.example" }
        val mime = String(GoogleOAuthProtocol.base64UrlDecode(GmailContent.rawMessage(EmailDraft(addresses, subject, "X".repeat(5000), "", ""))), StandardCharsets.UTF_8)
        assertTrue(mime.split("\r\n").all { it.toByteArray().size <= 998 })
        assertTrue(mime.substringAfter("\r\n\r\n").split("\r\n").all { it.length <= 76 })
        assertTrue(mime.contains("\r\n recipient")); assertTrue(mime.contains("Subject: =?UTF-8?B?"))
    }

    @Test fun mimeCharsetsAndEncodedHeadersAreDecodedWithoutAssumingUtf8() {
        val text = "Résumé, olé £"
        val payload = part("text/plain", text.toByteArray(Charset.forName("ISO-8859-1")), "ISO-8859-1")
        payload.getJSONArray("headers").put(JSONObject().put("name", "Subject").put("value", "=?ISO-8859-1?Q?R=E9sum=E9?= =?UTF-8?B?IPCfmoA=?="))
        val row = row(payload)
        assertEquals(text, row.getString("text"))
        assertEquals("Résumé 🚀", row.getString("subject")); assertFalse(row.getBoolean("truncated"))
    }

    @Test fun malformedCharsetBytesAndBase64AreMarkedTruncatedRatherThanInventingText() {
        val unknown = row(part("text/plain", "Hello".toByteArray(), "not-a-charset"))
        assertTrue(unknown.getBoolean("truncated")); assertEquals("", unknown.getString("text"))
        val invalid = row(part("text/plain", byteArrayOf(-1, -2), "UTF-8"))
        assertTrue(invalid.getBoolean("truncated"))
        for (encoded in listOf("a", "AA===", "AA%2B", "AB", "A=AA")) {
            assertTrue(encoded, runCatching { GmailContent.decode(encoded, 100) }.isFailure)
        }
        assertTrue(runCatching { GmailContent.decode("AAAA", 2) }.isFailure)
        assertArrayEquals(byteArrayOf(0), GmailContent.decode("AA==", 1))
    }

    @Test fun recursiveDepthPartCountAndDecodedByteBudgetAreBounded() {
        var nested = part("text/plain", "too deep".toByteArray())
        repeat(30) { nested = JSONObject().put("mimeType", "multipart/mixed").put("parts", JSONArray().put(nested)) }
        assertTrue(row(nested).getBoolean("truncated"))
        val parts = JSONArray()
        repeat(101) { parts.put(part("text/plain", "x".toByteArray())) }
        assertTrue(row(JSONObject().put("mimeType", "multipart/mixed").put("parts", parts)).getBoolean("truncated"))
        val tooManyBytes = row(part("text/plain", ByteArray(128 * 1024 + 1) { 65 }))
        assertTrue(tooManyBytes.getBoolean("truncated")); assertEquals("", tooManyBytes.getString("text"))
    }

    @Test fun nestedAlternativesPreferPlainAndTextAttachmentsAreNeverBodyContent() {
        val attached = part("text/plain", "ATTACHMENT MUST NOT BECOME BODY".toByteArray()).put("filename", "note.txt").put("partId", "2")
        attached.getJSONObject("body").put("attachmentId", "attachment1")
        val payload = JSONObject().put("mimeType", "multipart/mixed").put("parts", JSONArray()
            .put(JSONObject().put("mimeType", "multipart/alternative").put("parts", JSONArray()
                .put(part("text/plain", "Expected plain".toByteArray()))
                .put(part("text/html", "<p>Alternate HTML</p>".toByteArray())))).put(attached))
        val row = row(payload)
        assertEquals("Expected plain", row.getString("text"))
        val attachment = row.getJSONArray("attachments").getJSONObject(0)
        assertEquals("attachment1", attachment.getString("attachmentId")); assertEquals("m1", attachment.getString("messageId"))
        assertEquals("2", attachment.getString("partId")); assertFalse(row.toString().contains("ATTACHMENT MUST NOT"))
    }

    @Test fun htmlSanitizationRemovesScriptAndPreservesSupplementaryUnicodeEntities() {
        val text = row(part("text/html", "<script>hidden()</script><p>Hello&#x1f680;&amp; world</p>".toByteArray())).getString("text")
        assertEquals("Hello🚀& world", text); assertFalse(text.contains("hidden"))
    }

    @Test fun attachmentFilenamesUseRfc2231ContinuationsAndBinaryBase64() {
        val bytes = byteArrayOf(0, 127, -128, -1)
        val file = GoogleWorkspaceArtifact(bytes, "長い名前 résumé 🚀 ".repeat(10) + ".bin", "application/octet-stream", GmailContent.digest(bytes))
        val mime = String(GoogleOAuthProtocol.base64UrlDecode(GmailContent.rawMessage(EmailDraft("a@example.com", "S", "B", "", ""), attachments = listOf(file))))
        assertTrue(mime.contains("filename*0*=UTF-8''")); assertTrue(mime.contains("filename*1*="))
        assertTrue(mime.contains("AH+A/w==")); assertTrue(mime.split("\r\n").all { it.length <= 998 })
        assertFalse(mime.contains("長い名前"))
    }

    @Test fun draftReviewShowsHtmlSourceAndRejectsTruncationOrDuplicateRecipientHeaders() {
        val payload = part("text/html", "<a href=\"https://example.com/private\">read</a>".toByteArray())
        payload.getJSONArray("headers").put(JSONObject().put("name", "To").put("value", "a@example.com"))
            .put(JSONObject().put("name", "Subject").put("value", "S"))
        val message = JSONObject().put("payload", payload)
        assertTrue(GmailContent.review(message).draft.body.contains("https://example.com/private"))
        payload.getJSONArray("headers").put(JSONObject().put("name", "To").put("value", "hidden@example.com"))
        assertTrue(runCatching { GmailContent.review(message) }.isFailure)
        val long = part("text/plain", "x".repeat(19_501).toByteArray())
        long.getJSONArray("headers").put(JSONObject().put("name", "To").put("value", "a@example.com"))
        assertTrue(runCatching { GmailContent.review(JSONObject().put("payload", long)) }.isFailure)
    }

    @Test fun mailboxParserIgnoresDisplayAndCommentAddressesAndFailsClosedOnUnsupportedLists() {
        assertEquals(setOf("actual@example.com"), GmailContent.addresses("\"support@other.example\" <actual@example.com>"))
        assertEquals(setOf("actual@example.com"), GmailContent.addresses("actual@example.com (support@other.example)"))
        assertEquals(setOf("actual@example.com", "second@example.com"), GmailContent.addresses(
            "\"support@other.example, ignored@example.com\" <actual@example.com>, Second (ignored@example.com) <second@example.com>"))
        for (unsupported in listOf("Team: actual@example.com;", "actual@example.com,", "\"unterminated <actual@example.com>",
            "(unterminated actual@example.com", "Name <actual@example.com> hidden@example.com", "Name <actual@example.com><hidden@example.com>")) {
            assertTrue(unsupported, GmailContent.addresses(unsupported).isEmpty())
        }
    }


    @Test fun draftReviewApprovesOnlyRawMailboxRecipientsEvenWhenDisplayNamesDecodeToAddresses() {
        val encoded = "=?UTF-8?Q?support=40other.example=2C?= <actual@example.com>"
        val payload = part("text/plain", "Body".toByteArray())
        payload.getJSONArray("headers").put(JSONObject().put("name", "To").put("value", encoded))
            .put(JSONObject().put("name", "Subject").put("value", "S"))
        val message = JSONObject().put("payload", payload)
        assertTrue(GmailContent.header(message, "To").contains("support@other.example,"))
        assertEquals(setOf("actual@example.com"), GmailContent.mailboxAddresses(message, "To"))
        assertEquals("actual@example.com", GmailContent.review(message).draft.to)
        assertEquals("", GmailContent.review(message).draft.cc)
        assertEquals("", GmailContent.review(message).draft.bcc)
    }

}
