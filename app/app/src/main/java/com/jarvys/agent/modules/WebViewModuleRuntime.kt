package com.jarvys.agent.modules

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
import com.jarvys.agent.WebViewDiagnosticTrace
import org.json.JSONObject
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
        val diagnostics = WebViewDiagnosticTrace(
            localResourceLabel = { uri ->
                ModuleWebViewSecurity.moduleAssetPath(uri, module.bundleAssetDirectory)
                    ?: if (uri.scheme.equals("https", ignoreCase = true) &&
                        uri.host == ModuleWebViewSecurity.ASSET_AUTHORITY &&
                        uri.path?.startsWith("/module-artifacts/") == true) "delivered-artifact" else null
            },
            logMessage = { Log.w("ModuleWebView", it) },
        )
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        val bridge = ModuleCapabilityBridge(
            conversationId = request.conversationId!!,
            declaredCapabilities = module.capabilities,
            declaredArtifactTypes = module.artifactTypes.map { it.type },
            access = accessFactory(request, token, progress),
            complete = { raw -> result.complete(decodeResult(raw)) },
            fail = { code ->
                when {
                    code == FAILURE_SCRIPT_LOAD -> diagnostics.recordScriptLoadFailure(handlerPath)
                    code == FAILURE_EXPORT_MISSING -> diagnostics.recordExportMissing("$handlerPath#$exportName")
                    code.startsWith(FAILURE_EVALUATION_PREFIX) -> diagnostics.recordHandlerException(
                        "$handlerPath#$exportName", code.removePrefix(FAILURE_EVALUATION_PREFIX),
                    )
                }
                val fallback = "Module handler evaluation failed: $handlerPath#$exportName"
                result.complete(CoreToolResult.failure(diagnostics.failureMessage(fallback)))
            },
            thumbnailArtifactTypes = module.artifactTypes.filter { it.generatedThumbnail }.map { it.type },
        )
        val allowInitialBootstrapDocument = AtomicBoolean(true)
        return WebView(appContext).apply {
            ModuleWebViewSecurity.configure(this)
            addJavascriptInterface(bridge, JAVASCRIPT_BRIDGE_NAME)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    diagnostics.recordConsoleMessage(
                        consoleMessage.sourceId(), consoleMessage.lineNumber(),
                        consoleMessage.messageLevel().name, consoleMessage.message(),
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && request.url.scheme.equals("data", ignoreCase = true) &&
                        allowInitialBootstrapDocument.compareAndSet(true, false)) {
                        // loadDataWithBaseURL creates this host-owned document as data:; it is not a subresource.
                        return null
                    }
                    if (ModuleWebViewSecurity.moduleAssetPath(request.url, module.bundleAssetDirectory) == null) {
                        diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, request.isForMainFrame)
                        return ModuleWebViewSecurity.blockedResponse()
                    }
                    return try {
                        assetLoader.shouldInterceptRequest(request.url) ?: run {
                            diagnostics.recordAssetHandlerMiss(request.url)
                            ModuleWebViewSecurity.blockedResponse()
                        }
                    } catch (_: Exception) {
                        diagnostics.recordAssetHandlerError(request.url)
                        ModuleWebViewSecurity.blockedResponse()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val denied = ModuleWebViewSecurity.moduleAssetPath(request.url, module.bundleAssetDirectory) == null
                    if (denied) diagnostics.recordDenied(request.url, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE, request.isForMainFrame)
                    return denied
                }

                @Deprecated("Required for older WebView callbacks")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
                    val uri = Uri.parse(url)
                    val denied = ModuleWebViewSecurity.moduleAssetPath(uri, module.bundleAssetDirectory) == null
                    if (denied) diagnostics.recordDenied(uri, WebViewDiagnosticTrace.REASON_OUTSIDE_ALLOWED_SCOPE)
                    return denied
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    diagnostics.recordResourceError(request?.url, error?.errorCode ?: 0, request?.isForMainFrame)
                    if (request?.isForMainFrame == true) {
                        result.complete(CoreToolResult.failure(diagnostics.failureMessage("Module document failed to load.")))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    diagnostics.recordHttpError(request?.url, errorResponse?.statusCode ?: 0, request?.isForMainFrame)
                }
            }
            loadDataWithBaseURL(baseUrl, bootstrapHtml(handlerPath, exportName, request.arguments), "text/html", "UTF-8", null)
        }
    }

    private fun bootstrapHtml(handlerPath: String, exportName: String, arguments: Map<String, Any?>): String {
        val csp = ModuleWebViewSecurity.executionCsp()
        val handler = handlerPath.split('/').joinToString("/") { Uri.encode(it) }
        val export = jsStringLiteral(exportName)
        val args = JSONObject(arguments).toString()
        val argsJson = jsStringLiteral(args)
        return """<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${htmlAttribute(csp)}"></head><body>
            <script>(function(){const host=window.${JAVASCRIPT_BRIDGE_NAME};const script=document.createElement("script");let failed=false;function fail(code){if(!failed){failed=true;host.fail(code);}}function failEvaluation(error){let name="Error";try{if(error&&typeof error.name==="string")name=error.name;}catch(_){}fail("$FAILURE_EVALUATION_PREFIX"+name);}script.onerror=function(){fail("$FAILURE_SCRIPT_LOAD");};script.onload=function(){try{const fn=window[${export}];if(typeof fn!=="function"){fail("$FAILURE_EXPORT_MISSING");return;}Promise.resolve().then(function(){return fn(JSON.parse(${argsJson}),host);}).then(function(value){host.complete(JSON.stringify(value===undefined?{}:value));},failEvaluation);}catch(error){failEvaluation(error);}};script.src=${jsStringLiteral(handler)};document.head.appendChild(script);})();</script>
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

    private fun destroyWebView(view: WebView) {
        view.stopLoading()
        view.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
        view.webViewClient = WebViewClient()
        view.onPause()
        view.destroy()
    }

    private fun htmlAttribute(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun jsStringLiteral(value: String): String = JSONObject.quote(value).replace("<", "\\u003c")

    private companion object {
        const val JAVASCRIPT_BRIDGE_NAME = "Jarvys"
        const val FAILURE_SCRIPT_LOAD = "handler-script-load-failed"
        const val FAILURE_EXPORT_MISSING = "handler-export-missing"
        const val FAILURE_EVALUATION_PREFIX = "handler-evaluation-failed:"
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
