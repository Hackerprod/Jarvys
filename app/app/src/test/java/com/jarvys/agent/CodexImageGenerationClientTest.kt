package com.jarvys.agent

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexImageGenerationClientTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun requestForcesOnlyImageGenerationAndUsesPngWithoutCredentialOrImagePayload() {
        val prompt = "A cobalt lighthouse above winter waves"
        val request = CodexImageGenerationClient.buildRequest("gpt-5.4", prompt, "1536x1024")
        assertEquals("gpt-5.4", request.getString("model"))
        assertEquals(false, request.getBoolean("store"))
        assertEquals(true, request.getBoolean("stream"))
        assertEquals("image_generation", request.getJSONObject("tool_choice").getString("type"))
        val tool = request.getJSONArray("tools").getJSONObject(0)
        assertEquals("image_generation", tool.getString("type"))
        assertEquals("generate", tool.getString("action"))
        assertEquals("png", tool.getString("output_format"))
        assertEquals("1536x1024", tool.getString("size"))
        val input = request.getJSONArray("input").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text")
        assertTrue(input.contains(prompt))
        assertFalse(request.toString().contains("Bearer"))
        assertFalse(request.toString().contains("base64"))
        assertFalse(request.toString().contains("image_url"))
        assertTrue(CodexImageGenerationClient.supportedSizes().contains("auto"))
    }

    @Test
    fun finalImageItemReturnsValidatedPngAndRevisedPromptAfterIgnoringPartials() {
        val png = pngFixture()
        val encoded = Base64.getEncoder().encodeToString(png)
        val body = """
            data: {"type":"response.created","response":{"status":"in_progress"}}

            data: {"type":"response.image_generation_call.partial_image","partial_image_index":0,"partial_image_b64":"not-the-final-image"}

            data: {"type":"response.output_item.done","item":{"type":"image_generation_call","status":"completed","result":"$encoded","revised_prompt":"A revised cobalt lighthouse","size":"1024x1024","output_format":"png"}}

            data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"image_generation_call","status":"completed","result":"$encoded","revised_prompt":"A revised cobalt lighthouse","size":"1024x1024","output_format":"png"}]}}

            data: [DONE]

        """.trimIndent()

        val image = CodexImageGenerationClient.parseStream(body, "cobalt lighthouse")
        assertArrayEquals(png, image.bytes)
        assertEquals("A revised cobalt lighthouse", image.revisedPrompt)
        assertEquals("1024x1024", image.size)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun normalizedResponsesBodyReturnsImageAndKeepsRawSseSingleJsonAndProviderErrorsCompatible() {
        val png = pngFixture()
        val encoded = Base64.getEncoder().encodeToString(png)
        val item = JSONObject().put("type", "image_generation_call").put("status", "completed")
            .put("result", encoded).put("revised_prompt", "A revised lighthouse")
            .put("size", "1024x1024").put("output_format", "png")
        val events = listOf(
            JSONObject().put("type", "response.created").put("response", JSONObject().put("status", "in_progress")),
            JSONObject().put("type", "response.in_progress").put("response", JSONObject().put("status", "in_progress")),
            JSONObject().put("type", "response.image_generation_call.in_progress").put("item_id", "ig_1"),
            JSONObject().put("type", "response.image_generation_call.generating").put("item_id", "ig_1"),
            JSONObject().put("type", "response.image_generation_call.partial_image")
                .put("item_id", "ig_1").put("partial_image_index", 0).put("partial_image_b64", encoded),
            JSONObject().put("type", "response.output_item.done").put("item", item),
            JSONObject().put("type", "response.completed").put("response", JSONObject()
                .put("status", "completed").put("output", org.json.JSONArray().put(item))),
        )
        val normalized = events.joinToString("\n") { it.toString() }
        var sends = 0
        val generated = imageClient(200, normalized, 0L) { sends++ }
            .generate("i1b-normalized", "A cobalt lighthouse", null, CancellationToken.uncancellable())
        assertArrayEquals(png, generated.bytes)
        assertEquals("A revised lighthouse", generated.revisedPrompt)
        assertEquals(1, sends)

        val rawSse = events.joinToString("\n\n", postfix = "\n\n") { event ->
            "event: ${event.optString("type")}\ndata: ${event}"
        }
        assertArrayEquals(png, CodexImageGenerationClient.parseStream(rawSse, "A cobalt lighthouse").bytes)

        val singleJson = JSONObject().put("type", "response.completed").put("response", JSONObject()
            .put("output", org.json.JSONArray().put(item)))
        assertArrayEquals(png, CodexImageGenerationClient.parseStream(singleJson.toString(), "single response").bytes)
        assertArrayEquals(png, CodexImageGenerationClient.parseStream(singleJson.toString(2), "pretty response").bytes)

        val providerFailures = listOf(
            JSONObject().put("type", "response.failed").put("response", JSONObject().put("error", JSONObject()
                .put("code", "backend_failure").put("message", "provider rejected request"))),
            JSONObject().put("type", "error").put("error", JSONObject()
                .put("code", "backend_error").put("message", "provider transport error")),
        )
        providerFailures.forEach { event ->
            val error = runCatching {
                imageClient(200, event.toString(), 0L) { }
                    .generate("i1b-normalized", "SECRET_PROMPT", null, CancellationToken.uncancellable())
            }.exceptionOrNull() as CodexImageGenerationException
            assertEquals(event.getString("type"), error.lastEvent)
            assertFalse("no_image_in_stream" == error.errorCode)
            assertTrue(error.apiMessage.startsWith("provider "))
            assertFalse(error.apiMessage.contains("SECRET_PROMPT"))
        }

        val incompleteBody = listOf(
            JSONObject().put("type", "response.created"),
            JSONObject().put("type", "response.in_progress"),
            JSONObject().put("type", "response.image_generation_call.partial_image")
                .put("partial_image_b64", "PRIVATE_PARTIAL_BYTES"),
        ).joinToString("\n") { it.toString() }
        val incomplete = runCatching {
            imageClient(200, incompleteBody, 0L) { }
                .generate("i1b-normalized", "SECRET_PROMPT", null, CancellationToken.uncancellable())
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals("no_image_in_stream", incomplete.errorCode)
        assertEquals("events=response.created,response.in_progress,response.image_generation_call.partial_image", incomplete.lastEvent)
        assertFalse(incomplete.lastEvent.contains("PRIVATE_PARTIAL_BYTES"))
        assertFalse(incomplete.apiMessage.contains("SECRET_PROMPT"))

        val truncated = runCatching {
            imageClient(200, JSONObject().put("type", "response.created").toString(), 0L) { }
                .generate("i1b-normalized", "SECRET_PROMPT", null, CancellationToken.uncancellable())
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals("stream_truncated", truncated.errorCode)
        assertEquals("events=response.created", truncated.lastEvent)
        assertFalse(truncated.lastEvent.contains("SECRET_PROMPT"))
    }

    @Test
    fun failedIncompleteEmptyAndCorruptStreamsAreTypedFailuresWithLastEvent() {
        val policy = runCatching {
            CodexImageGenerationClient.parseStream(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"content_policy_violation\",\"message\":\"blocked\"}}}\n\n",
                "sensitive prompt")
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals(CodexImageGenerationException.Kind.POLICY, policy.kind)
        assertEquals("content_policy_violation", policy.errorCode)
        assertFalse(policy.apiMessage.contains("sensitive prompt"))

        val incomplete = runCatching {
            CodexImageGenerationClient.parseStream(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n",
                "request")
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals(CodexImageGenerationException.Kind.INCOMPLETE, incomplete.kind)
        assertEquals("response.incomplete", incomplete.lastEvent)

        val empty = runCatching { CodexImageGenerationClient.parseStream("", "request") }
            .exceptionOrNull() as CodexImageGenerationException
        assertEquals("empty_stream", empty.errorCode)

        val corrupt = runCatching {
            CodexImageGenerationClient.parseStream(
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"not-base64***\"}]}}\n\n",
                "request")
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals(CodexImageGenerationException.Kind.INVALID_IMAGE, corrupt.kind)
    }

    @Test
    fun statusFailuresAreDistinctAndNeverRetryTheImageOperation() {
        val cases = listOf(
            Triple(401, "{\"error\":{\"code\":\"invalid_token\",\"message\":\"login required\"}}", CodexImageGenerationException.Kind.SESSION),
            Triple(403, "{\"error\":{\"code\":\"image_generation_not_enabled\",\"message\":\"plan has no image access\"}}", CodexImageGenerationException.Kind.ACCESS),
            Triple(404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"model unavailable\"}}", CodexImageGenerationException.Kind.ACCESS),
            Triple(429, "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"quota exhausted\"}}", CodexImageGenerationException.Kind.QUOTA),
        )
        cases.forEach { (status, body, expected) ->
            var attempts = 0
            val error = runCatching {
                imageClient(status, body, if (status == 429) 8_000L else 0L) { attempts++ }
                    .generate("i1-session", "private lighthouse prompt", null, CancellationToken.uncancellable())
            }.exceptionOrNull() as CodexImageGenerationException
            assertEquals(expected, error.kind)
            assertEquals(1, attempts)
            assertFalse(error.apiMessage.contains("private lighthouse prompt"))
            if (status == 429) assertEquals(8_000L, error.retryAfterMillis)
        }

        var policyAttempts = 0
        val policy = runCatching {
            imageClient(400, "{\"error\":{\"code\":\"content_policy_violation\",\"message\":\"rejected\"}}", 0L) {
                policyAttempts++
            }.generate("i1-session", "new prompt", null, CancellationToken.uncancellable())
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals(CodexImageGenerationException.Kind.POLICY, policy.kind)
        assertEquals(1, policyAttempts)
    }

    @Test
    fun invalidSizeIsRejectedAgainstThePublishedSizeOptionsBeforeARequestIsSent() {
        var attempts = 0
        val error = runCatching {
            imageClient(200, "", 0L) { attempts++ }
                .generate("i1-session", "A lighthouse", "900x900", CancellationToken.uncancellable())
        }.exceptionOrNull() as CodexImageGenerationException
        assertEquals("unsupported_size", error.errorCode)
        assertTrue(error.apiMessage.contains("1024x1024"))
        assertEquals(0, attempts)
    }

    @Test
    fun sharedCodexExecutorRefreshes401OnceAndUsesFreshCredentialsOnRetry() {
        val credentialCalls = mutableListOf<Boolean>()
        val sentCredentials = mutableListOf<String>()
        var sends = 0
        val response = CodexAuthenticatedRequestExecutor.execute(
            JSONObject().put("input", "safe"), "i1-session", CancellationToken.uncancellable(), "gpt-5.4",
            object : CodexAuthenticatedRequestExecutor.CredentialProvider {
                override fun get(forceRefresh: Boolean, token: CancellationToken): Any {
                    credentialCalls += forceRefresh
                    return if (forceRefresh) "fresh" else "original"
                }
            },
            object : CodexAuthenticatedRequestExecutor.Sender {
                override fun send(request: JSONObject, credentials: Any, sessionId: String,
                                  token: CancellationToken): ProviderHttp.Response {
                    sentCredentials += credentials as String
                    sends++
                    return if (sends == 1) ProviderHttp.Response(401, "unauthorized")
                    else ProviderHttp.Response(200, "ok")
                }
            })
        assertEquals(200, response.status)
        assertEquals(listOf(false, true), credentialCalls)
        assertEquals(listOf("original", "fresh"), sentCredentials)
        assertEquals(2, sends)
    }

    private fun imageClient(status: Int, body: String, retryAfter: Long, onSend: () -> Unit): CodexImageGenerationClient {
        val settings = ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENAI_CODEX)
            setOpenAiModel("gpt-5.4")
        }
        return CodexImageGenerationClient(settings) { _, _, _ ->
            onSend()
            ProviderHttp.Response(status, body, body, retryAfter)
        }
    }

    private fun pngFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(34, 87, 142))
        return ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
    }
}
