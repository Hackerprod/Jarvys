package com.jarvys.agent.modules

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.jarvys.agent.ConversationModuleCapabilityAccess
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.PreviewResponsePolicy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs declared bundle handlers in an isolated, offscreen WebView. */
class WebViewModuleRuntime @JvmOverloads constructor(
    context: Context,
    private val accessFactory: (ModuleExecutionRequest, CancellationToken, CoreTool.ProgressListener?) -> ModuleCapabilityAccess =
        { request, token, progress -> ConversationModuleCapabilityAccess(context.applicationContext, request, token, progress) },
) : ModuleRuntime {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun execute(
        request: ModuleExecutionRequest,
        token: CancellationToken,
        progress: CoreTool.ProgressListener?,
    ): CoreToolResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return CoreToolResult.failure("Module handlers must run off the main thread.")
        }
        token.throwIfCancelled()
        val result = CompletableFuture<CoreToolResult>()
        val host = arrayOfNulls<WebView>(1)
        val destroyed = AtomicBoolean(false)
        val destroyHost = {
            if (destroyed.compareAndSet(false, true)) mainHandler.post { host[0]?.let(::destroyWebView) }
        }
        val cancelWait = token.registerCancelAction {
            result.completeExceptionally(CancellationException("Module execution was cancelled"))
            destroyHost()
        }
        try {
            mainHandler.post {
                if (result.isDone) return@post
                try {
                    host[0] = createWebView(request, token, progress, result)
                } catch (failure: Exception) {
                    result.complete(CoreToolResult.failure(failure.message ?: "Could not start module handler."))
                    host[0]?.let(::destroyWebView)
                }
            }
            while (true) {
                token.throwIfCancelled()
                try {
                    return result.get(100, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    // Poll only for cancellation; handler work has no invented wall-clock limit.
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Module execution was interrupted").also { it.initCause(interrupted) }
                } catch (wrapped: ExecutionException) {
                    val cause = wrapped.cause ?: wrapped
                    if (cause is CancellationException) throw cause
                    return CoreToolResult.failure(cause.message ?: "Module handler failed.")
                }
            }
        } catch (cancelled: CancellationException) {
            return CoreToolResult.failure(cancelled.message ?: "Module execution was cancelled.")
        } finally {
            cancelWait.run()
            destroyHost()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        request: ModuleExecutionRequest,
        token: CancellationToken,
        progress: CoreTool.ProgressListener?,
        result: CompletableFuture<CoreToolResult>,
    ): WebView {
        require(request.conversationId?.isNotBlank() == true) { "A conversation scope is required for module execution." }
        val module = request.module
        val (handlerPath, exportName) = request.tool.handler.split('#', limit = 2).let { parts ->
            require(parts.size == 2) { "The module handler declaration is invalid." }
            parts[0] to parts[1]
        }
        require(module.id.matches(Regex("[A-Za-z][A-Za-z0-9._-]*"))) { "The module bundle id is invalid." }
        require(ModuleWorkspacePath.isSafeBundlePath(handlerPath)) { "The module handler path is invalid." }
        val bundleAssetPath = module.bundleAssetDirectory.split('/').joinToString("/") { Uri.encode(it) }
        val baseUrl = "https://appassets.androidplatform.net/assets/$bundleAssetPath/"
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        val bridge = ModuleCapabilityBridge(
            conversationId = request.conversationId!!,
            declaredCapabilities = module.capabilities,
            declaredArtifactTypes = module.artifactTypes.map { it.type },
            access = accessFactory(request, token, progress),
            complete = { raw -> result.complete(decodeResult(raw)) },
            fail = { message -> result.complete(CoreToolResult.failure(message.ifBlank { "Module handler failed." })) },
        )
        return WebView(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            addJavascriptInterface(bridge, JAVASCRIPT_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                    if (!isModuleAssetUrl(request.url, module.bundleAssetDirectory)) return blockedResponse()
                    return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                    !isModuleAssetUrl(request.url, module.bundleAssetDirectory)

                @Deprecated("Required for older WebView callbacks")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean =
                    !isModuleAssetUrl(Uri.parse(url), module.bundleAssetDirectory)

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        result.complete(CoreToolResult.failure("The module bundle could not be loaded."))
                    }
                }
            }
            loadDataWithBaseURL(baseUrl, bootstrapHtml(handlerPath, exportName, request.arguments), "text/html", "UTF-8", null)
        }
    }

    private fun bootstrapHtml(handlerPath: String, exportName: String, arguments: Map<String, Any?>): String {
        val csp = PreviewResponsePolicy.interactiveHeaders().getValue("Content-Security-Policy")
        val handler = handlerPath.split('/').joinToString("/") { Uri.encode(it) }
        val export = JSONObject.quote(exportName)
        val args = JSONObject(arguments).toString()
        val argsJson = JSONObject.quote(args)
        return """<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${htmlAttribute(csp)}"></head><body>
            <script src="${htmlAttribute(handler)}"></script>
            <script>(async function(){try{const fn=window[${export}];if(typeof fn!=="function")throw new Error("Declared module handler is missing");const result=await fn(JSON.parse(${argsJson}),window.${JAVASCRIPT_BRIDGE_NAME});window.${JAVASCRIPT_BRIDGE_NAME}.complete(JSON.stringify(result===undefined?{}:result));}catch(e){window.${JAVASCRIPT_BRIDGE_NAME}.fail(String(e&&e.message||e));}})();</script>
            </body></html>""".trimIndent()
    }

    private fun decodeResult(raw: String): CoreToolResult = try {
        val value = org.json.JSONTokener(raw).nextValue()
        if (value is JSONObject) {
            val success = value.optBoolean("success", true)
            val content = if (value.has("content")) value.optString("content") else value.toString()
            if (!success) CoreToolResult.failure(content)
            else if (value.optBoolean("finishRun", false)) CoreToolResult.finish(content)
            else if (value.optBoolean("completeContentRequired", false)) CoreToolResult.complete(content)
            else value.optString("previewId").takeIf(String::isNotBlank)?.let { CoreToolResult.preview(content, it) }
                ?: CoreToolResult.success(content)
        } else CoreToolResult.success(if (value is String) value else raw)
    } catch (_: Exception) {
        CoreToolResult.success(raw)
    }

    private fun isModuleAssetUrl(uri: Uri, bundleAssetDirectory: String): Boolean {
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.encodedAuthority != ASSET_AUTHORITY) return false
        val path = uri.encodedPath ?: return false
        val expectedPrefix = "/assets/$bundleAssetDirectory/"
        if (!path.startsWith(expectedPrefix)) return false
        if (ENCODED_PATH_CONTROL.containsMatchIn(path)) return false
        val decoded = Uri.decode(path.removePrefix(expectedPrefix))
        return ModuleWorkspacePath.isSafeBundlePath(decoded)
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Forbidden", PreviewResponsePolicy.interactiveHeaders(),
        ByteArrayInputStream("Module resource is outside its bundled assets.".toByteArray(Charsets.UTF_8)),
    )

    private fun destroyWebView(view: WebView) {
        view.stopLoading()
        view.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
        view.webViewClient = WebViewClient()
        view.onPause()
        view.destroy()
    }

    private fun htmlAttribute(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val JAVASCRIPT_BRIDGE_NAME = "Jarvys"
        const val ASSET_AUTHORITY = "appassets.androidplatform.net"
        val ENCODED_PATH_CONTROL = Regex("%(?:2e|2f|5c|00|25)", RegexOption.IGNORE_CASE)
    }
}

internal object ModuleWorkspacePath {
    fun isSafeWorkspacePath(path: String): Boolean {
        if (!isSafeBundlePath(path) || path.startsWith("/")) return false
        val first = path.substringBefore('/').lowercase(Locale.ROOT)
        return first !in setOf("memory", "skills", "attachments", "project")
    }

    fun isSafeBundlePath(path: String): Boolean = path.isNotBlank() &&
        '\\' !in path && ':' !in path && '%' !in path && '?' !in path && '#' !in path &&
        path.none(Char::isISOControl) && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
}
