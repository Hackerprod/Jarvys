package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** No network, account authorization, storage paths, credentials or real Google files. */
class DriveConnectorContractTest {
    private data class Request(val scope: String, val method: String, val url: String, val body: ByteArray?,
        val contentType: String, val headers: Map<String, String>, val limit: Int?, val expectedEpoch: Long?)
    private class FakeAuthorization : GoogleRestAuthorization {
        val requests = mutableListOf<Request>()
        val text = ArrayDeque<GoogleHttpResponse>()
        val binary = ArrayDeque<GoogleBinaryResponse>()
        val grants = GoogleOAuthProtocol.ALLOWED_SCOPES.toMutableSet()
        var onRequest: ((Request, CancellationToken) -> Unit)? = null
        var beforeAcquire: (() -> Unit)? = null
        var onScopeCheck: (() -> Unit)? = null
        var epoch = 0L
        override fun currentAuthorizationEpoch() = epoch
        override fun isScopeGranted(scope: String): Boolean { onScopeCheck?.invoke(); return scope in grants }
        override fun request(scope: String, method: String, url: String, body: String?, contentType: String): GoogleHttpResponse =
            error("Connector must use cancellable transport")
        override fun requestCancellable(scope: String, method: String, url: String, body: String?, contentType: String,
                                        token: CancellationToken, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleHttpResponse {
            token.throwIfCancelled()
            beforeAcquire?.invoke()
            check(expectedAuthorizationEpoch == null || expectedAuthorizationEpoch == epoch) { "Authorization epoch changed" }
            val request = Request(scope, method, url, body?.toByteArray(), contentType, requestHeaders, null, expectedAuthorizationEpoch)
            requests += request
            onRequest?.invoke(request, token)
            return text.removeFirst()
        }
        override fun requestBytes(scope: String, method: String, url: String, body: ByteArray?, contentType: String,
                                  token: CancellationToken, maxResponseBytes: Int, requestHeaders: Map<String, String>, expectedAuthorizationEpoch: Long?): GoogleBinaryResponse {
            token.throwIfCancelled()
            beforeAcquire?.invoke()
            check(expectedAuthorizationEpoch == null || expectedAuthorizationEpoch == epoch) { "Authorization epoch changed" }
            val request = Request(scope, method, url, body?.copyOf(), contentType, requestHeaders, maxResponseBytes, expectedAuthorizationEpoch)
            requests += request
            onRequest?.invoke(request, token)
            return binary.removeFirst()
        }
        fun metadata(file: JSONObject = ordinaryFile(), etag: String? = null) {
            text += GoogleHttpResponse(200, file.toString(), etag?.let { mapOf("ETag" to it) } ?: emptyMap())
        }
        fun written(id: String = "new-file") {
            binary += GoogleBinaryResponse(200, JSONObject().put("id", id).put("name", "new.txt").put("mimeType", "text/plain").toString().toByteArray())
        }
    }
    private class FakeArtifacts : GoogleWorkspaceArtifactSink {
        val published = mutableListOf<GoogleWorkspaceArtifact>()
        val sources = mutableMapOf<String, GoogleWorkspaceArtifact>()
        var reads = 0
        override fun publish(bytes: ByteArray, name: String, mime: String, token: CancellationToken): JSONObject {
            token.throwIfCancelled()
            published += GoogleWorkspaceArtifact(bytes.copyOf(), name, mime, hash(bytes))
            return JSONObject().put("artifact_id", DELIVERED_ID).put("name", name).put("mime", mime).put("bytes", bytes.size)
        }
        override fun read(artifactId: String, token: CancellationToken): GoogleWorkspaceArtifact {
            token.throwIfCancelled()
            reads++
            return sources[artifactId] ?: error("No current-chat artifact")
        }
    }

    @Test fun firstFourOperationsRemainCompatibleAndEveryWriteRequiresApproval() {
        val auth = FakeAuthorization()
        val definition = DriveConnector.definition(auth, FakeArtifacts())
        assertEquals(listOf("search_files", "get_file_metadata", "read_file", "create_file"), definition.operations.take(4).map { it.name })
        assertEquals(setOf("create_file", "update_file", "create_folder"), definition.operations.filter { it.write }.map { it.name }.toSet())
        assertTrue(definition.operations.filter { it.write }.all { !it.autonomyAllowed })
        assertTrue(definition.operations.none { it.name.contains("delete") || it.name.contains("share") || it.name.contains("permission") })
        assertTrue(definition.connectionAccessGranted!!.invoke())
        auth.grants.remove(GoogleOAuthProtocol.DRIVE_READ)
        assertFalse(definition.connectionAccessGranted!!.invoke())
        for (operation in listOf(DriveConnector.CREATE_FILE, DriveConnector.UPDATE_FILE, DriveConnector.CREATE_FOLDER)) {
            failure { definition.runtime!!.invoke(operation, JSONObject(), token()) }
        }
        assertTrue(auth.requests.isEmpty())
    }

    @Test fun searchEscapesQueryFiltersAndReturnsOneOpaquePage() {
        val auth = FakeAuthorization().apply {
            text += GoogleHttpResponse(200, JSONObject().put("files", JSONArray().put(ordinaryFile()))
                .put("nextPageToken", "opaque+/=next").put("incompleteSearch", true).toString())
        }
        val result = DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.SEARCH_FILES, JSONObject()
            .put("query", "owner's \\ file").put("parent_id", "parent-1").put("mime", "text/plain")
            .put("max_results", 3).put("next_page_token", "opaque+/=prev"), token())
        val request = auth.requests.single()
        val query = query(request.url)
        assertEquals("GET", request.method)
        assertEquals("/drive/v3/files", URI(request.url).path)
        assertEquals(GoogleOAuthProtocol.DRIVE_READ, request.scope)
        assertEquals("3", query["pageSize"])
        assertEquals("opaque+/=prev", query["pageToken"])
        assertEquals("trashed = false and (name contains 'owner\\'s \\\\ file' or fullText contains 'owner\\'s \\\\ file') and 'parent-1' in parents and mimeType = 'text/plain'", query["q"])
        assertEquals("user", query["corpora"])
        assertEquals("false", query["includeItemsFromAllDrives"])
        assertTrue(query["fields"]!!.contains("capabilities"))
        assertEquals("opaque+/=next", result.getString("next_page_token"))
        assertTrue(result.getBoolean("has_more"))
        assertTrue(result.getBoolean("incomplete_search"))
        assertTrue(result.getBoolean("untrusted_content"))
    }

    @Test fun emptyPageCanStillHaveNextTokenAndNeverAutoFetches() {
        val auth = FakeAuthorization().apply { text += GoogleHttpResponse(200, """{"files":[],"nextPageToken":"next"}""") }
        val result = DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.SEARCH_FILES, JSONObject().put("query", ""), token())
        assertEquals(0, result.getJSONArray("items").length())
        assertEquals("next", result.getString("next_page_token"))
        assertEquals(1, auth.requests.size)
    }

    @Test fun sharedDriveSearchUsesAllDocumentedFlagsAndRejectsConflictingScope() {
        val auth = FakeAuthorization().apply { text += GoogleHttpResponse(200, """{"files":[]}""") }
        val runtime = DriveConnector(auth, FakeArtifacts())
        runtime.invoke(DriveConnector.SEARCH_FILES, JSONObject().put("query", "").put("drive_id", "shared-1"), token())
        val params = query(auth.requests.single().url)
        assertEquals("shared-1", params["driveId"])
        assertEquals("drive", params["corpora"])
        assertEquals("true", params["supportsAllDrives"])
        assertEquals("true", params["includeItemsFromAllDrives"])
        for (args in listOf(
            JSONObject().put("query", "").put("corpora", "drive"),
            JSONObject().put("query", "").put("drive_id", "shared-1").put("corpora", "user"),
            JSONObject().put("query", "").put("drive_id", "shared-1").put("supports_all_drives", false),
            JSONObject().put("query", "").put("corpora", "allDrives").put("include_items_from_all_drives", false),
        )) failure { runtime.invoke(DriveConnector.SEARCH_FILES, args, token()) }
        assertEquals(1, auth.requests.size)
    }

    @Test fun searchValidatesBoundsIdsTypesAndMimeBeforeRequest() {
        val auth = FakeAuthorization()
        val runtime = DriveConnector(auth, FakeArtifacts())
        listOf(
            JSONObject().put("query", "x".repeat(513)),
            JSONObject().put("query", "").put("max_results", 26),
            JSONObject().put("query", "").put("max_results", 1.5),
            JSONObject().put("query", "").put("next_page_token", "x".repeat(2049)),
            JSONObject().put("query", "").put("next_page_token", "bad\nvalue"),
            JSONObject().put("query", "").put("parent_id", "../other"),
            JSONObject().put("query", "").put("supports_all_drives", "true"),
            JSONObject().put("query", "").put("mime", "text/plain\r\nX-Test: yes"),
        ).forEach { failure { runtime.invoke(DriveConnector.SEARCH_FILES, it, token()) } }
        assertTrue(auth.requests.isEmpty())
    }

    @Test fun metadataReturnsCapabilitiesAndVersionWithoutPermissionList() {
        val auth = FakeAuthorization().apply { metadata() }
        val result = DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.GET_FILE_METADATA, JSONObject().put("id", "file-1"), token())
        val row = result.getJSONArray("items").getJSONObject(0)
        assertTrue(row.getJSONObject("capabilities").getBoolean("canEdit"))
        assertEquals("7", row.getString("version"))
        assertTrue(row.getBoolean("isAppAuthorized"))
        assertEquals("true", query(auth.requests.single().url)["supportsAllDrives"])
        assertFalse(row.has("permissions"))
    }

    @Test fun legacyInlineReadKeepsOneMiBLimitAndTwelveKiBUtf8Preview() {
        val content = "😀".repeat(4_000)
        val auth = FakeAuthorization().apply {
            metadata(ordinaryFile().put("size", content.toByteArray().size.toString()))
            binary += GoogleBinaryResponse(200, content.toByteArray())
        }
        val result = DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.READ_FILE, JSONObject().put("id", "file-1"), token())
        val row = result.getJSONArray("items").getJSONObject(0)
        assertTrue(row.getBoolean("truncated"))
        assertTrue(result.getBoolean("truncated"))
        assertEquals(12 * 1024, row.getString("text").toByteArray().size)
        assertFalse(Character.isHighSurrogate(row.getString("text").last()))
        assertEquals(SafDocumentPolicy.MAX_BYTES, auth.requests.last().limit)
        val oversized = FakeAuthorization().apply { metadata(ordinaryFile().put("size", "2000000")) }
        failure { DriveConnector(oversized, FakeArtifacts()).invoke(DriveConnector.READ_FILE, JSONObject().put("id", "file-1"), token()) }
        assertEquals(1, oversized.requests.size)
    }

    @Test fun inlineReadRejectsMalformedUtf8InsteadOfCorruptingBinary() {
        val auth = FakeAuthorization().apply { metadata(ordinaryFile().put("size", "2")); binary += GoogleBinaryResponse(200, byteArrayOf(0xc3.toByte(), 0x28)) }
        failure { DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.READ_FILE, JSONObject().put("id", "file-1"), token()) }
    }

    @Test fun binaryDownloadDeliversExactBytesWithoutPuttingContentInTranscript() {
        val bytes = byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 13, 10, 1)
        val artifacts = FakeArtifacts()
        val auth = FakeAuthorization().apply {
            metadata(ordinaryFile().put("mimeType", "application/pdf").put("name", "../report.pdf").put("size", bytes.size.toString()))
            binary += GoogleBinaryResponse(200, bytes, mapOf("Content-Type" to "application/pdf"))
        }
        val result = DriveConnector(auth, artifacts).invoke(DriveConnector.DOWNLOAD_FILE, JSONObject().put("id", "file-1"), token())
        val delivered = artifacts.published.single()
        assertArrayEquals(bytes, delivered.bytes)
        assertEquals("application/pdf", delivered.mime)
        assertFalse(delivered.name.contains('/'))
        assertEquals("media", query(auth.requests.last().url)["alt"])
        assertEquals("GET", auth.requests.last().method)
        assertEquals(8 * 1024 * 1024, auth.requests.last().limit)
        val row = result.getJSONArray("items").getJSONObject(0)
        assertFalse(row.has("text"))
        assertFalse(row.has("base64"))
        assertEquals(DELIVERED_ID, row.getJSONObject("artifact").getString("artifact_id"))
        assertEquals(hash(bytes), row.getString("content_sha256"))
    }

    @Test fun downloadsRefuseOversizeNativeFolderAndDisallowedCapabilityBeforeTransfer() {
        val files = listOf(
            ordinaryFile().put("size", DriveConnector.MAX_TRANSFER_BYTES.toLong() + 1),
            ordinaryFile().put("mimeType", DriveConnector.GOOGLE_FOLDER),
            ordinaryFile().put("mimeType", DriveConnector.GOOGLE_DOC),
            ordinaryFile().put("capabilities", JSONObject().put("canDownload", false)),
        )
        for (file in files) {
            val auth = FakeAuthorization().apply { metadata(file) }
            val sink = FakeArtifacts()
            failure { DriveConnector(auth, sink).invoke(DriveConnector.DOWNLOAD_FILE, JSONObject().put("id", "file-1"), token()) }
            assertEquals(1, auth.requests.size)
            assertTrue(sink.published.isEmpty())
        }
    }

    @Test fun downloadChecksActualByteCountAndMimeEvenWithFakeTransport() {
        for (response in listOf(
            GoogleBinaryResponse(200, ByteArray(DriveConnector.MAX_TRANSFER_BYTES + 1)),
            GoogleBinaryResponse(200, "failure page".toByteArray(), mapOf("Content-Type" to "text/html")),
        )) {
            val sink = FakeArtifacts()
            val auth = FakeAuthorization().apply { metadata(ordinaryFile().put("mimeType", "application/pdf")); binary += response }
            failure { DriveConnector(auth, sink).invoke(DriveConnector.DOWNLOAD_FILE, JSONObject().put("id", "file-1"), token()) }
            assertTrue(sink.published.isEmpty())
        }
    }

    @Test fun downloadRefusesPartialOrTruncatedSuccessfulResponses() {
        for (response in listOf(
            GoogleBinaryResponse(206, byteArrayOf(1, 2, 3, 4)),
            GoogleBinaryResponse(200, byteArrayOf(1, 2)),
        )) {
            val auth = FakeAuthorization().apply { metadata(); binary += response }
            val sink = FakeArtifacts()
            failure { DriveConnector(auth, sink).invoke(DriveConnector.DOWNLOAD_FILE, JSONObject().put("id", "file-1"), token()) }
            assertTrue(sink.published.isEmpty())
        }
    }

    @Test fun exportsDocsSheetsAndSlidesUseExactEndpointMimeAndNativeArtifacts() {
        val formats = listOf(DriveConnector.GOOGLE_DOC to "application/pdf", DriveConnector.GOOGLE_SHEET to "text/csv",
            DriveConnector.GOOGLE_SLIDES to "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        for ((native, mime) in formats) {
            val sink = FakeArtifacts()
            val bytes = byteArrayOf(1, 0xff.toByte(), 2)
            val auth = FakeAuthorization().apply { metadata(ordinaryFile().put("mimeType", native).removeSize()); binary += GoogleBinaryResponse(200, bytes) }
            val result = DriveConnector(auth, sink).invoke(DriveConnector.EXPORT_FILE, JSONObject().put("id", "file-1").put("mime", mime), token())
            assertEquals("/drive/v3/files/file-1/export", URI(auth.requests.last().url).path)
            assertEquals(mapOf("mimeType" to mime), query(auth.requests.last().url))
            assertArrayEquals(bytes, sink.published.single().bytes)
            assertEquals(mime, sink.published.single().mime)
            if (native == DriveConnector.GOOGLE_SHEET) assertTrue(result.toString().contains("first sheet"))
        }
    }

    @Test fun invalidExportMimeCombinationNeverFetchesContent() {
        val auth = FakeAuthorization().apply { metadata(ordinaryFile().put("mimeType", DriveConnector.GOOGLE_SHEET)) }
        failure { DriveConnector(auth, FakeArtifacts()).invoke(DriveConnector.EXPORT_FILE,
            JSONObject().put("id", "file-1").put("mime", "text/plain"), token()) }
        assertEquals(1, auth.requests.size)
    }

    @Test fun multipartCreateUsesUploadEndpointMethodBoundariesAndExactUtf8Bytes() {
        val auth = FakeAuthorization().apply { written() }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val args = createArgs("Hello á😀\r\nnext line")
        val preparation = runtime.prepareWrite(DriveConnector.CREATE_FILE, args, token())
        assertTrue(auth.requests.isEmpty())
        assertTrue(preparation.approval.lines.any { it.contains("My Drive root") })
        assertTrue(preparation.approval.lines.any { it == "SHA-256: ${hash(args.getString("content").toByteArray())}" })
        val result = runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token())
        val request = auth.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https", URI(request.url).scheme)
        assertEquals("www.googleapis.com", URI(request.url).host)
        assertEquals("/upload/drive/v3/files", URI(request.url).path)
        assertEquals("multipart", query(request.url)["uploadType"])
        assertEquals(GoogleOAuthProtocol.DRIVE_FILE, request.scope)
        val boundary = request.contentType.substringAfter("boundary=")
        val body = requireNotNull(request.body).toString(Charsets.UTF_8)
        assertTrue(body.startsWith("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
        assertTrue(body.endsWith("\r\n--$boundary--\r\n"))
        assertTrue(body.contains("\r\nContent-Type: text/plain\r\n\r\nHello á😀\r\nnext line\r\n"))
        assertEquals(3, Regex(Regex.escape("--$boundary")).findAll(body).count())
        assertEquals("file_created", result.getString("status"))
        failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        assertEquals(1, auth.requests.size)
    }

    @Test fun artifactUploadFreezesBytesBeforeApprovalAndPreservesNonUtf8() {
        val bytes = byteArrayOf(0, 0xff.toByte(), 2, 3)
        val original = bytes.copyOf()
        val sink = FakeArtifacts().apply { sources[ATTACHMENT_ID] = GoogleWorkspaceArtifact(bytes, "report.pdf", "application/pdf", hash(bytes)) }
        val auth = FakeAuthorization().apply { written() }
        val runtime = DriveConnector(auth, sink)
        val args = JSONObject().put("name", "report.pdf").put("mime", "application/pdf").put("artifact_id", ATTACHMENT_ID)
        val preparation = runtime.prepareWrite(DriveConnector.CREATE_FILE, args, token())
        bytes.fill(9)
        val result = runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token())
        assertEquals(1, sink.reads)
        assertTrue(requireNotNull(auth.requests.single().body).containsSequence(original))
        assertEquals(hash(original), result.getJSONArray("items").getJSONObject(0).getString("content_sha256"))
        assertTrue(preparation.approval.lines.any { it.contains(ATTACHMENT_ID) })
    }

    @Test fun uploadRejectsUnboundPathsRawBinaryMimeMismatchAndStaleArtifactHash() {
        val auth = FakeAuthorization()
        val sink = FakeArtifacts().apply { sources[ATTACHMENT_ID] = GoogleWorkspaceArtifact(byteArrayOf(1), "x.pdf", "application/pdf", "not-a-hash") }
        val runtime = DriveConnector(auth, sink)
        val args = listOf(
            JSONObject().put("name", "x").put("mime", "application/pdf").put("content", "raw bytes"),
            JSONObject().put("name", "x").put("mime", "application/pdf").put("artifact_id", "/sdcard/private.pdf"),
            JSONObject().put("name", "x").put("mime", "application/pdf").put("artifact_id", ATTACHMENT_ID).put("content", "both"),
            JSONObject().put("name", "x").put("mime", "text/plain").put("artifact_id", ATTACHMENT_ID),
            JSONObject().put("name", "x").put("mime", "application/pdf").put("artifact_id", ATTACHMENT_ID),
            createArgs("text").put("mime", "text/plain\r\nInjected: yes"),
            createArgs("text").put("mime", DriveConnector.GOOGLE_DOC),
            createArgs("text").put("permissions", JSONArray()),
        )
        args.forEach { failure { runtime.prepareWrite(DriveConnector.CREATE_FILE, it, token()) } }
        assertTrue(auth.requests.isEmpty())
    }

    @Test fun uploadBoundIncludesMultipartFraming() {
        val bytes = ByteArray(DriveConnector.MAX_UPLOAD_BYTES)
        val sink = FakeArtifacts().apply { sources[ATTACHMENT_ID] = GoogleWorkspaceArtifact(bytes, "data.bin", "application/octet-stream", hash(bytes)) }
        val auth = FakeAuthorization()
        failure { DriveConnector(auth, sink).prepareWrite(DriveConnector.CREATE_FILE,
            JSONObject().put("name", "data.bin").put("mime", "application/octet-stream").put("artifact_id", ATTACHMENT_ID), token()) }
        assertTrue(auth.requests.isEmpty())
    }

    @Test fun createFolderPreflightsParentAndUsesMetadataOnlyPost() {
        val folder = parentFile().put("driveId", "shared-1")
        val auth = FakeAuthorization().apply {
            metadata(folder); metadata(folder)
            text += GoogleHttpResponse(200, """{"id":"folder-new","name":"Project","mimeType":"${DriveConnector.GOOGLE_FOLDER}"}""")
        }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val args = JSONObject().put("name", "Project").put("parent_id", "parent-1").put("drive_id", "shared-1")
        val preparation = runtime.prepareWrite(DriveConnector.CREATE_FOLDER, args, token())
        assertTrue(preparation.approval.lines.any { it.contains("parent-1") })
        assertTrue(preparation.approval.lines.any { it.contains("inherits") })
        val result = runtime.invokePrepared(DriveConnector.CREATE_FOLDER, preparation.executionArguments, preparation, token())
        val request = auth.requests.last()
        assertEquals("POST", request.method)
        assertEquals("/drive/v3/files", URI(request.url).path)
        assertFalse(query(request.url).containsKey("uploadType"))
        assertEquals("true", query(request.url)["supportsAllDrives"])
        val body = JSONObject(requireNotNull(request.body).toString(Charsets.UTF_8))
        assertEquals("parent-1", body.getJSONArray("parents").getString(0))
        assertEquals(DriveConnector.GOOGLE_FOLDER, body.getString("mimeType"))
        assertEquals("folder_created", result.getString("status"))
        assertTrue(auth.requests.all { it.scope == GoogleOAuthProtocol.DRIVE_FILE })
    }

    @Test fun sharedDriveCreateCannotImplicitlyWriteToRootOrWrongDrive() {
        val auth = FakeAuthorization()
        val runtime = DriveConnector(auth, FakeArtifacts())
        failure { runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("x").put("drive_id", "shared-1"), token()) }
        assertTrue(auth.requests.isEmpty())
        auth.metadata(parentFile().put("driveId", "other-drive"))
        failure { runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("x").put("parent_id", "parent-1").put("drive_id", "shared-1"), token()) }
        assertTrue(auth.requests.all { it.method == "GET" })
    }

    @Test fun updateUsesDriveFilePreflightsPatchAndStrongEtag() {
        val auth = FakeAuthorization().apply { metadata(etag = "\"revision-7\""); metadata(etag = "\"revision-7\""); written("file-1") }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val args = updateArgs()
        val preparation = runtime.prepareWrite(DriveConnector.UPDATE_FILE, args, token())
        assertTrue(preparation.approval.lines.any { it.contains("Current version: 7") })
        runtime.invokePrepared(DriveConnector.UPDATE_FILE, preparation.executionArguments, preparation, token())
        assertEquals(listOf("GET", "GET", "PATCH"), auth.requests.map { it.method })
        assertTrue(auth.requests.all { it.scope == GoogleOAuthProtocol.DRIVE_FILE })
        val request = auth.requests.last()
        assertEquals("/upload/drive/v3/files/file-1", URI(request.url).path)
        assertEquals(mapOf("If-Match" to "\"revision-7\""), request.headers)
        assertFalse(requireNotNull(request.body).toString(Charsets.UTF_8).contains("\"parents\""))
    }

    @Test fun updateWithoutStrongEtagDisclosesResidualRaceAndNeverInventsConditionalVersion() {
        val auth = FakeAuthorization().apply { metadata(etag = "W/\"weak\""); metadata(etag = "W/\"weak\""); written("file-1") }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val preparation = runtime.prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token())
        assertTrue(preparation.approval.lines.any { it.contains("concurrent edit may still race") })
        val result = runtime.invokePrepared(DriveConnector.UPDATE_FILE, preparation.executionArguments, preparation, token())
        assertTrue(auth.requests.last().headers.isEmpty())
        assertEquals("version_preflight_only", result.getJSONArray("items").getJSONObject(0).getString("concurrency_protection"))
    }

    @Test fun updateRefusesChangedVersionNameParentsCapabilitiesOrEtagAfterApproval() {
        val mutations: List<(JSONObject) -> JSONObject> = listOf(
            { it.put("version", "8") }, { it.put("name", "Someone renamed it") },
            { it.put("parents", JSONArray().put("other-parent")) },
            { it.put("capabilities", JSONObject().put("canEdit", false)) },
        )
        for (mutation in mutations) {
            val auth = FakeAuthorization().apply { metadata(); metadata(mutation(ordinaryFile())) }
            val runtime = DriveConnector(auth, FakeArtifacts())
            val preparation = runtime.prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token())
            failure { runtime.invokePrepared(DriveConnector.UPDATE_FILE, preparation.executionArguments, preparation, token()) }
            assertTrue(auth.requests.all { it.method == "GET" })
        }
        val auth = FakeAuthorization().apply { metadata(etag = "\"a\""); metadata(etag = "\"b\"") }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val preparation = runtime.prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token())
        failure { runtime.invokePrepared(DriveConnector.UPDATE_FILE, preparation.executionArguments, preparation, token()) }
        assertEquals(2, auth.requests.size)
    }

    @Test fun updateRefusesFilesOutsideAppScopeNativeDocumentsAndMissingVersion() {
        val files = listOf(ordinaryFile().put("isAppAuthorized", false), ordinaryFile().put("mimeType", DriveConnector.GOOGLE_DOC),
            ordinaryFile().put("version", ""), ordinaryFile().put("trashed", true))
        for (file in files) {
            val auth = FakeAuthorization().apply { metadata(file) }
            failure { DriveConnector(auth, FakeArtifacts()).prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token()) }
            assertEquals(listOf("GET"), auth.requests.map { it.method })
        }
        val auth = FakeAuthorization().apply { text += GoogleHttpResponse(404, "{}") }
        val error = failure { DriveConnector(auth, FakeArtifacts()).prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token()) }
        assertTrue(error.message.orEmpty().contains("broader Drive access will not be requested"))
        assertEquals(GoogleOAuthProtocol.DRIVE_FILE, auth.requests.single().scope)
    }

    @Test fun changedArgumentsAndForeignPreparationCannotBeExecuted() {
        val auth = FakeAuthorization()
        val first = DriveConnector(auth, FakeArtifacts())
        val second = DriveConnector(auth, FakeArtifacts())
        val preparation = first.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
        failure { second.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        failure { first.invokePrepared(DriveConnector.CREATE_FOLDER, preparation.executionArguments, preparation, token()) }
        preparation.executionArguments.put("content", "unapproved")
        failure { first.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        assertTrue(auth.requests.isEmpty())
    }

    @Test fun ambiguousWriteFailureAndConflictNeverAutomaticallyReplay() {
        for (status in listOf(500, 503, 408, 412)) {
            val auth = FakeAuthorization().apply { binary += GoogleBinaryResponse(status, "{}".toByteArray()) }
            val runtime = DriveConnector(auth, FakeArtifacts())
            val preparation = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
            failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
            failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
            assertEquals(1, auth.requests.size)
        }
        val auth = FakeAuthorization().apply { onRequest = { _, _ -> throw IOException("socket closed") } }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val preparation = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
        failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        assertEquals(1, auth.requests.size)
    }

    @Test fun rootCreateFolderAndUpdateApprovalsCannotCrossAccountChanges() {
        val operations = listOf(
            DriveConnector.CREATE_FILE to createArgs("account A content"),
            DriveConnector.CREATE_FOLDER to JSONObject().put("name", "Account A folder"),
            DriveConnector.UPDATE_FILE to updateArgs(),
        )
        for ((operation, args) in operations) {
            val auth = FakeAuthorization().apply { epoch = 41L; if (operation == DriveConnector.UPDATE_FILE) metadata() }
            val runtime = DriveConnector(auth, FakeArtifacts())
            val prepared = runtime.prepareWrite(operation, args, token())
            auth.epoch = 42L // Disconnect/reconnect as another account, with the same granted scopes.
            val error = failure { runtime.invokePrepared(operation, prepared.executionArguments, prepared, token()) }
            assertTrue(error.message.orEmpty().contains("authorization changed"))
            assertTrue(auth.requests.none { it.method == "POST" || it.method == "PATCH" })
            assertEquals(if (operation == DriveConnector.UPDATE_FILE) 1 else 0, auth.requests.size)
            assertTrue(auth.requests.all { it.expectedEpoch == 41L })
        }
    }

    @Test fun preparationChecksEpochEvenForRootWritesWithoutMetadata() {
        for ((operation, args) in listOf(
            DriveConnector.CREATE_FILE to createArgs("content"),
            DriveConnector.CREATE_FOLDER to JSONObject().put("name", "Folder"),
            DriveConnector.UPDATE_FILE to updateArgs(),
        )) {
            val auth = FakeAuthorization().apply { epoch = 10L; onScopeCheck = { epoch = 11L } }
            failure { DriveConnector(auth, FakeArtifacts()).prepareWrite(operation, args, token()) }
            assertTrue(auth.requests.isEmpty())
        }
    }

    @Test fun metadataPreparationAndPostApprovalPreflightsCarryCapturedEpoch() {
        val auth = FakeAuthorization().apply { epoch = 73L; metadata(); metadata(); written("file-1") }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val prepared = runtime.prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token())
        runtime.invokePrepared(DriveConnector.UPDATE_FILE, prepared.executionArguments, prepared, token())
        assertEquals(listOf("GET", "GET", "PATCH"), auth.requests.map { it.method })
        assertTrue(auth.requests.all { it.expectedEpoch == 73L })
        val parentAuth = FakeAuthorization().apply {
            epoch = 74L; metadata(parentFile()); metadata(parentFile())
            text += GoogleHttpResponse(200, "{\"id\":\"new-folder\"}")
        }
        val folders = DriveConnector(parentAuth, FakeArtifacts())
        val folder = folders.prepareWrite(DriveConnector.CREATE_FOLDER, JSONObject().put("name", "Folder").put("parent_id", "parent-1"), token())
        folders.invokePrepared(DriveConnector.CREATE_FOLDER, folder.executionArguments, folder, token())
        assertEquals(listOf("GET", "GET", "POST"), parentAuth.requests.map { it.method })
        assertTrue(parentAuth.requests.all { it.expectedEpoch == 74L })
    }

    @Test fun expectedEpochClosesCheckToRequestAcquisitionRaceForRootWrites() {
        for ((operation, args) in listOf(
            DriveConnector.CREATE_FILE to createArgs("content"),
            DriveConnector.CREATE_FOLDER to JSONObject().put("name", "Folder"),
        )) {
            val auth = FakeAuthorization().apply { epoch = 20L }
            val runtime = DriveConnector(auth, FakeArtifacts())
            val prepared = runtime.prepareWrite(operation, args, token())
            auth.beforeAcquire = { auth.epoch = 21L }
            failure { runtime.invokePrepared(operation, prepared.executionArguments, prepared, token()) }
            assertTrue(auth.requests.isEmpty())
        }
    }

    @Test fun epochChangeDuringPostApprovalMetadataStopsUpdateBeforePatch() {
        val auth = FakeAuthorization().apply { epoch = 31L; metadata(); metadata() }
        val runtime = DriveConnector(auth, FakeArtifacts())
        val prepared = runtime.prepareWrite(DriveConnector.UPDATE_FILE, updateArgs(), token())
        auth.onRequest = { _, _ -> auth.epoch = 32L }
        failure { runtime.invokePrepared(DriveConnector.UPDATE_FILE, prepared.executionArguments, prepared, token()) }
        assertEquals(listOf("GET", "GET"), auth.requests.map { it.method })
        assertTrue(auth.requests.all { it.expectedEpoch == 31L })
    }

    @Test fun uncertainWriteJournalBlocksNewPreparationAcrossRuntimeInstances() {
        val journal = GoogleWorkspaceWriteJournals.inMemory()
        val auth = FakeAuthorization().apply { binary += GoogleBinaryResponse(503, "{}".toByteArray()) }
        val first = DriveConnector(auth, FakeArtifacts(), journal)
        val prepared = first.prepareWrite(DriveConnector.CREATE_FILE, createArgs("unique payload"), token())
        failure { first.invokePrepared(DriveConnector.CREATE_FILE, prepared.executionArguments, prepared, token()) }
        val restarted = DriveConnector(auth, FakeArtifacts(), journal)
        val error = failure { restarted.prepareWrite(DriveConnector.CREATE_FILE, createArgs("unique payload"), token()) }
        assertTrue(error.message.orEmpty().contains("automatic replay is blocked"))
        assertEquals(1, auth.requests.size)
    }

    @Test fun malformedSuccessRetainsJournalWhileVerifiedSuccessAndDefinitiveDenialResolveIt() {
        for (response in listOf(
            GoogleBinaryResponse(200, "not json".toByteArray()),
            GoogleBinaryResponse(200, "{}".toByteArray()),
            GoogleBinaryResponse(408, "{}".toByteArray()),
        )) {
            val journal = GoogleWorkspaceWriteJournals.inMemory()
            val auth = FakeAuthorization().apply { binary += response }
            val runtime = DriveConnector(auth, FakeArtifacts(), journal)
            val prepared = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("same payload"), token())
            failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, prepared.executionArguments, prepared, token()) }
            failure { DriveConnector(auth, FakeArtifacts(), journal).prepareWrite(DriveConnector.CREATE_FILE, createArgs("same payload"), token()) }
            assertEquals(1, auth.requests.size)
        }
        for (status in listOf(200, 400, 403, 412, 429)) {
            val auth = FakeAuthorization().apply { binary += GoogleBinaryResponse(status, "{\"id\":\"created\"}".toByteArray()) }
            val journal = GoogleWorkspaceWriteJournals.inMemory()
            val runtime = DriveConnector(auth, FakeArtifacts(), journal)
            val prepared = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("same payload"), token())
            runCatching { runtime.invokePrepared(DriveConnector.CREATE_FILE, prepared.executionArguments, prepared, token()) }
            // A separately approved new operation is permitted only after a verified terminal outcome.
            DriveConnector(auth, FakeArtifacts(), journal).prepareWrite(DriveConnector.CREATE_FILE, createArgs("same payload"), token())
            assertEquals(1, auth.requests.size)
        }
    }

    @Test fun journalReserveFailurePreventsDispatchAndResolveFailureReturnsWarning() {
        val failReserve = object : GoogleWorkspaceWriteJournal {
            override fun isUncertain(intentHash: String) = false
            override fun reserve(intentHash: String) { error("Local journal cannot be saved") }
            override fun resolve(intentHash: String) = false
        }
        val auth = FakeAuthorization()
        val runtime = DriveConnector(auth, FakeArtifacts(), failReserve)
        val prepared = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
        failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, prepared.executionArguments, prepared, token()) }
        assertTrue(auth.requests.isEmpty())
        val unresolved = object : GoogleWorkspaceWriteJournal {
            var reserved = false
            override fun isUncertain(intentHash: String) = reserved
            override fun reserve(intentHash: String) { reserved = true }
            override fun resolve(intentHash: String) = false
        }
        auth.written()
        val confirmed = DriveConnector(auth, FakeArtifacts(), unresolved)
        val snapshot = confirmed.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
        val result = confirmed.invokePrepared(DriveConnector.CREATE_FILE, snapshot.executionArguments, snapshot, token())
        assertEquals("file_created", result.getString("status"))
        assertTrue(result.getString("warning").contains("Do not retry"))
        failure { confirmed.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token()) }
    }

    @Test fun cancellationBeforeOrDuringReadStopsTransferAndArtifactPublication() {
        val auth = FakeAuthorization()
        val sink = FakeArtifacts()
        val runtime = DriveConnector(auth, sink)
        val cancelled = CancellationToken.cancellable().apply { cancel() }
        assertTrue(failure { runtime.invoke(DriveConnector.SEARCH_FILES, JSONObject().put("query", ""), cancelled) } is CancellationException)
        assertTrue(auth.requests.isEmpty())
        auth.metadata()
        auth.onRequest = { _, t -> t.cancel() }
        assertTrue(failure { runtime.invoke(DriveConnector.DOWNLOAD_FILE, JSONObject().put("id", "file-1"), CancellationToken.cancellable()) } is CancellationException)
        assertEquals(1, auth.requests.size)
        assertTrue(sink.published.isEmpty())
        val transfer = FakeAuthorization().apply {
            metadata(); binary += GoogleBinaryResponse(200, byteArrayOf(1))
            onRequest = { request, t -> if (request.limit != null) t.cancel() }
        }
        assertTrue(failure { DriveConnector(transfer, sink).invoke(DriveConnector.DOWNLOAD_FILE,
            JSONObject().put("id", "file-1"), CancellationToken.cancellable()) } is CancellationException)
        assertTrue(sink.published.isEmpty())
    }

    @Test fun deniedApprovalNeverWritesAndRevokedWriteScopeStopsExecution() {
        val auth = FakeAuthorization()
        val runtime = DriveConnector(auth, FakeArtifacts())
        val preparation = runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token())
        auth.grants.remove(GoogleOAuthProtocol.DRIVE_FILE)
        failure { runtime.invokePrepared(DriveConnector.CREATE_FILE, preparation.executionArguments, preparation, token()) }
        failure { runtime.prepareWrite(DriveConnector.CREATE_FILE, createArgs("approved"), token()) }
        assertTrue(auth.requests.isEmpty())
        auth.grants.add(GoogleOAuthProtocol.DRIVE_FILE)
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(2_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { gate.resolve(id, ApprovalDecision.DENIED) }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, gate)
        val definition = DriveConnector.definition(auth, FakeArtifacts())
        registry.register(definition)
        registry.connect(DriveConnector.ID)
        failure { registry.invoke(definition, definition.operations[3], createArgs("denied"), token()) }
        assertTrue(auth.requests.isEmpty())
    }

    companion object {
        private const val ATTACHMENT_ID = "attachment:123e4567-e89b-12d3-a456-426614174000"
        private const val DELIVERED_ID = "delivered:123e4567-e89b-12d3-a456-426614174001"
        private fun token() = CancellationToken.uncancellable()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun createArgs(content: String) = JSONObject().put("name", "new.txt").put("mime", "text/plain").put("content", content)
        private fun updateArgs() = JSONObject().put("id", "file-1").put("mime", "text/plain").put("content", "replacement")
        private fun ordinaryFile() = JSONObject().put("id", "file-1").put("name", "File.txt").put("mimeType", "text/plain")
            .put("size", "4").put("version", "7").put("modifiedTime", "2026-10-08T00:00:00Z")
            .put("isAppAuthorized", true).put("trashed", false).put("parents", JSONArray().put("parent-1"))
            .put("capabilities", JSONObject().put("canDownload", true).put("canEdit", true).put("canModifyContent", true).put("canRename", true))
        private fun parentFile() = JSONObject().put("id", "parent-1").put("name", "Parent").put("mimeType", DriveConnector.GOOGLE_FOLDER)
            .put("version", "1").put("trashed", false).put("isAppAuthorized", true).put("capabilities", JSONObject().put("canAddChildren", true))
        private fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        private fun JSONObject.removeSize(): JSONObject { remove("size"); return this }
        private fun ByteArray.containsSequence(other: ByteArray): Boolean = (0..size - other.size).any { at ->
            other.indices.all { this[at + it] == other[it] }
        }
        private fun failure(block: () -> Any?): Throwable = runCatching(block).exceptionOrNull() ?: throw AssertionError("Expected failure")
    }
}
