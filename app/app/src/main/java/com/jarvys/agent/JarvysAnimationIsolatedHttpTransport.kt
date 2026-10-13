package com.jarvys.agent

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * NEW functional correction: an animation-only client with no process cookie/auth state.
 * Default TLS trust and hostname verification remain enabled. Redirects are checked before each call.
 */
class JarvysAnimationIsolatedHttpTransport internal constructor(
    private val callFactory: Call.Factory
) : JarvysAnimationTransport {
    constructor() : this(client)

    companion object {
        private val client by lazy { newClient() }
        private val redirects = setOf(301, 302, 303, 307, 308)

        internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun get(uri: URI, token: CancellationToken): JarvysAnimationTransport.Response {
        var current = JarvysAnimationSite.checked(uri.toString())
        val visited = HashSet<URI>()
        while (true) {
            token.throwIfCancelled()
            if (!visited.add(current) || visited.size > 8) throw IOException("Animation redirect loop")
            // A fresh request carries no caller headers, cookies, authentication or body.
            val request = Request.Builder().url(current.toString()).get()
                .header("Accept-Encoding", "identity").build()
            val call = callFactory.newCall(request)
            val unregister = token.registerCancelAction { call.cancel() }
            var response: Response? = null
            try {
                token.throwIfCancelled()
                val result = call.execute()
                response = result
                token.throwIfCancelled()
                if (result.code in redirects) {
                    val location = result.header("Location") ?: throw IOException("Missing redirect URL")
                    current = JarvysAnimationSite.checked(current.resolve(location).toString())
                    result.close()
                    unregister.run()
                    continue
                }
                if (result.code != 200) throw IOException("Animation request was unsuccessful")
                val encoding = result.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw IOException("Unexpected animation transfer encoding")
                }
                val rawLength = result.header("Content-Length")
                val length = if (rawLength == null) -1L else rawLength.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IOException("Invalid animation response length")
                val stream = result.body.byteStream()
                return object : JarvysAnimationTransport.Response {
                    private val closed = AtomicBoolean()
                    override val status: Int = result.code
                    override val contentLength: Long = length
                    override val body: InputStream = stream
                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            try { result.close() } finally { unregister.run() }
                        }
                    }
                }
            } catch (failure: Throwable) {
                try { response?.close() } finally { unregister.run(); call.cancel() }
                throw failure
            }
        }
    }
}
