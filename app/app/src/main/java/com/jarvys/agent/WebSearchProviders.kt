package com.jarvys.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

data class WebSearchItem(val id: String, val index: Int, val title: String, val url: String, val text: String)
data class WebSearchOutcome(val items: List<WebSearchItem> = emptyList(), val reason: String? = null, val message: String? = null, val source: String? = null)
data class WebPageOutcome(val url: String, val title: String, val text: String, val truncated: Boolean,
                          val reason: String? = null, val message: String? = null)
data class WebHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray, val truncated: Boolean)

fun interface WebHttpTransport {
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int,
            followRedirects: Boolean): WebHttpResponse

    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, maxBytes: Int): WebHttpResponse {
        throw UnsupportedOperationException("POST is not supported by this transport")
    }
}

fun interface WebDnsResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemWebDnsResolver : WebDnsResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

interface WebSearchProvider {
    val sourceName: String get() = "bing"
    fun search(query: String, maxResults: Int, acceptLanguage: String): WebSearchOutcome
}

class BingLocalSearchProvider(
    private val transport: WebHttpTransport = UrlConnectionWebHttpTransport,
) : WebSearchProvider {
    override fun search(query: String, maxResults: Int, acceptLanguage: String): WebSearchOutcome {
        val request = makeRequest(query, maxResults, acceptLanguage)
            ?: return invalidRequest(query, maxResults)
        return try {
            val response = transport.get(
                url = request.url,
                headers = request.headers,
                timeoutMs = TIMEOUT_MS,
                maxBytes = MAX_HTML_BYTES,
                followRedirects = true,
            )
            when {
                response.status != 200 -> WebSearchOutcome(
                    reason = "http_${response.status}", message = "Bing returned HTTP ${response.status}",
                )
                response.truncated -> WebSearchOutcome(
                    reason = "parse_failed", message = "Bing search response exceeded the 2 MiB safety limit",
                )
                else -> BingHtmlParser.parse(String(response.body, StandardCharsets.UTF_8), maxResults)
            }
        } catch (_: java.net.SocketTimeoutException) {
            WebSearchOutcome(reason = "timeout", message = "Bing search timed out")
        } catch (_: TimeoutException) {
            WebSearchOutcome(reason = "timeout", message = "Bing search timed out")
        } catch (error: Exception) {
            WebSearchOutcome(reason = "network", message = safeMessage(error))
        }
    }

    private fun makeRequest(query: String, maxResults: Int, acceptLanguage: String): SearchRequest? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || cleanQuery.length > MAX_QUERY_CHARS || maxResults !in 1..MAX_RESULTS) return null
        val language = safeAcceptLanguage(acceptLanguage)
        val locale = Locale.forLanguageTag(language.substringBefore(',').substringBefore(';'))
        val languageCode = locale.language.ifBlank { "en" }
        val regionCode = locale.country.ifBlank { languageCode.ifBlank { "US" } }.uppercase(Locale.ROOT)
        val marketCode = "$languageCode-$regionCode"
        val address = buildString {
            append("https://www.bing.com/search?q=").append(encodeQuery(cleanQuery))
            append("&mkt=").append(encodeQuery(marketCode))
            append("&setlang=").append(encodeQuery(languageCode))
            append("&cc=").append(encodeQuery(locale.country.ifBlank { "US" }))
        }
        return SearchRequest(
            url = address,
            headers = linkedMapOf(
                "User-Agent" to DESKTOP_USER_AGENT,
                "Accept-Language" to language,
                "Accept" to "text/html,application/xhtml+xml",
            ),
        )
    }

    private fun invalidRequest(query: String, maxResults: Int): WebSearchOutcome {
        val cleanQuery = query.trim()
        return if (cleanQuery.isBlank() || cleanQuery.length > MAX_QUERY_CHARS) {
            WebSearchOutcome(reason = "parse_failed", message = "Search query must be between 1 and $MAX_QUERY_CHARS characters")
        } else {
            WebSearchOutcome(reason = "parse_failed", message = "max_results must be between 1 and $MAX_RESULTS")
        }
    }

    private data class SearchRequest(val url: String, val headers: Map<String, String>)

    companion object {
        const val MAX_QUERY_CHARS = 512
        const val MAX_RESULTS = 10
        const val TIMEOUT_MS = 20_000
        const val MAX_HTML_BYTES = 2 * 1024 * 1024
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        internal fun safeAcceptLanguage(value: String): String = value
            .take(100).filter { it == ',' || it == '-' || it == ';' || it == '=' || it == '.' || it == '*' || it.isLetterOrDigit() || it.isWhitespace() }
            .trim().ifBlank { "en-US,en;q=0.9" }

        internal fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}

/** Exa's public Search API. API credentials are provided at construction and never included in errors. */
class ExaSearchProvider(
    private val apiKey: String,
    private val transport: WebHttpTransport = UrlConnectionWebHttpTransport,
) : WebSearchProvider {
    override val sourceName: String get() = "exa"
    override fun search(query: String, maxResults: Int, acceptLanguage: String): WebSearchOutcome {
        val normalized = query.trim()
        if (normalized.isBlank() || normalized.length > BingLocalSearchProvider.MAX_QUERY_CHARS || maxResults !in 1..BingLocalSearchProvider.MAX_RESULTS)
            return WebSearchOutcome(reason = "parse_failed", message = "Invalid search query or result count")
        if (apiKey.isBlank()) return WebSearchOutcome(reason = "auth_required", message = "Exa API key is missing")
        return try {
            val request = JSONObject().put("query", normalized).put("type", "auto").put("numResults", maxResults)
                .put("contents", JSONObject().put("highlights", true))
            val response = transport.post("https://api.exa.ai/search", mapOf(
                "x-api-key" to apiKey, "Content-Type" to "application/json", "Accept" to "application/json",
                "Accept-Language" to BingLocalSearchProvider.safeAcceptLanguage(acceptLanguage),
            ), request.toString().toByteArray(StandardCharsets.UTF_8), BingLocalSearchProvider.TIMEOUT_MS, MAX_RESPONSE_BYTES)
            if (response.status !in 200..299) return WebSearchOutcome(reason = "http_${response.status}", message = "Exa returned HTTP ${response.status}")
            if (response.truncated) return WebSearchOutcome(reason = "parse_failed", message = "Exa response exceeded the safety limit")
            val results = JSONObject(String(response.body, StandardCharsets.UTF_8)).optJSONArray("results") ?: JSONArray()
            val items = mutableListOf<WebSearchItem>()
            val seen = hashSetOf<String>()
            for (i in 0 until results.length()) {
                if (items.size >= maxResults) break
                val row = results.optJSONObject(i) ?: continue
                val url = WebSearchUrlPolicy.decodePublicResultUrl(row.optString("url")) ?: continue
                if (!seen.add(url)) continue
                val title = row.optString("title").take(500).ifBlank { url }
                val highlights = row.optJSONArray("highlights")
                val text = (0 until (highlights?.length() ?: 0)).mapNotNull { highlights?.optString(it) }
                    .filter(String::isNotBlank).joinToString(" ").take(2_000)
                items += WebSearchItem(WebSearchCitationIds.next(), items.size + 1, title, url, text)
            }
            if (items.isEmpty()) WebSearchOutcome(reason = "no_results", message = "Exa returned no safe results") else WebSearchOutcome(items)
        } catch (_: java.net.SocketTimeoutException) {
            WebSearchOutcome(reason = "timeout", message = "Exa search timed out")
        } catch (_: TimeoutException) {
            WebSearchOutcome(reason = "timeout", message = "Exa search timed out")
        } catch (error: Exception) {
            WebSearchOutcome(reason = "network", message = "Exa search request failed")
        }
    }

    companion object { const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
}

/** Extracts result records from an HTML token tree and rejects pages that no longer look like results. */
object BingHtmlParser {
    private val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val blockElements = setOf("address", "article", "aside", "blockquote", "br", "div", "footer", "h1", "h2", "h3",
        "h4", "h5", "h6", "header", "li", "main", "ol", "p", "section", "ul")
    private val namedEntities = mapOf(
        "amp" to "&", "apos" to "'", "gt" to ">", "lt" to "<", "quot" to "\"", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "copy" to "©", "reg" to "®", "trade" to "™",
    )

    private sealed interface HtmlPart
    private data class HtmlText(val value: String) : HtmlPart
    private class HtmlElement(
        val name: String,
        val attributes: Map<String, String>,
        val parent: HtmlElement?,
    ) : HtmlPart {
        val children = mutableListOf<HtmlPart>()
        fun hasClass(name: String) = attributes["class"].orEmpty().split(Regex("\\s+")).any { it.equals(name, true) }
        fun attr(name: String) = attributes[name.lowercase(Locale.ROOT)]
    }

    fun parse(html: String, maxResults: Int): WebSearchOutcome {
        val document = readTree(html)
        val visibleText = textContent(document).lowercase(Locale.ROOT)
        val pageTitle = descendants(document).firstOrNull { it.name == "title" }?.let(::textContent).orEmpty()
        if (isAccessGate(pageTitle, visibleText, document)) {
            return WebSearchOutcome(reason = "blocked_or_captcha", message = "Bing returned a CAPTCHA, consent or access-block page")
        }

        val resultEntries = descendants(document).filter { it.name == "li" && it.hasClass("b_algo") }
        if (resultEntries.isEmpty()) {
            val emptyResultsRegion = descendants(document).any { node ->
                node.attr("id").equals("b_results", true) && textContent(node).isBlank()
            }
            return if (visibleText.contains("no results") || visibleText.contains("no results found") || emptyResultsRegion) {
                WebSearchOutcome(reason = "no_results", message = "Bing returned no search results")
            } else {
                WebSearchOutcome(reason = "parse_failed", message = "Bing page did not contain recognizable result blocks")
            }
        }

        val limit = maxResults.coerceIn(1, BingLocalSearchProvider.MAX_RESULTS)
        val uniqueTargets = HashSet<String>()
        val collected = ArrayList<WebSearchItem>(min(resultEntries.size, limit))
        for (entry in resultEntries) {
            if (collected.size == limit) break
            val heading = descendants(entry).firstOrNull { it.name == "h2" } ?: continue
            val title = textContent(heading).take(500)
            if (title.isBlank()) continue
            val link = linkForHeading(heading, entry) ?: continue
            val target = WebSearchUrlPolicy.decodePublicResultUrl(htmlDecode(link)) ?: continue
            if (!uniqueTargets.add(target)) continue
            val snippet = resultSnippet(entry).take(2_000)
            collected += WebSearchItem(WebSearchCitationIds.next(), collected.size + 1, title, target, snippet)
        }
        return if (collected.isEmpty()) {
            WebSearchOutcome(reason = "parse_failed", message = "Bing result blocks could not be converted into safe title and URL pairs")
        } else WebSearchOutcome(items = collected)
    }

    fun cleanHtml(html: String): String = textContent(readTree(html)).replace(Regex("[\\p{Z}\\s]+"), " ").trim()

    fun htmlDecode(value: String): String {
        val output = StringBuilder(value.length)
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] != '&') {
                output.append(value[cursor++])
                continue
            }
            val semicolon = value.indexOf(';', cursor + 1).takeIf { it in (cursor + 2)..(cursor + 13) }
            if (semicolon == null) {
                output.append(value[cursor++])
                continue
            }
            val entity = value.substring(cursor + 1, semicolon)
            val decoded = when {
                entity.startsWith("#x", true) -> entity.drop(2).toIntOrNull(16)?.let(::validCodePoint)
                entity.startsWith('#') -> entity.drop(1).toIntOrNull()?.let(::validCodePoint)
                else -> namedEntities[entity]
            }
            if (decoded == null) {
                output.append(value, cursor, semicolon + 1)
            } else output.append(decoded)
            cursor = semicolon + 1
        }
        return output.toString()
    }

    private fun validCodePoint(codePoint: Int): String? =
        if (codePoint in 0..Character.MAX_CODE_POINT && codePoint !in 0xd800..0xdfff) {
            String(Character.toChars(codePoint))
        } else null

    private fun linkForHeading(heading: HtmlElement, entry: HtmlElement): String? {
        var ancestor = heading.parent
        while (ancestor != null && ancestor !== entry) {
            if (ancestor.name == "a") ancestor.attr("href")?.takeIf(String::isNotBlank)?.let { return it }
            ancestor = ancestor.parent
        }
        return descendants(heading).firstOrNull { it.name == "a" }?.attr("href")
            ?: descendants(entry).firstOrNull { it.name == "a" && it.attr("href")?.isNotBlank() == true }?.attr("href")
    }

    private fun resultSnippet(entry: HtmlElement): String {
        val nodes = descendants(entry)
        val caption = nodes.firstOrNull { it.hasClass("b_caption") }
        if (caption != null) {
            val paragraph = descendants(caption).firstOrNull { it.name == "p" }
            val text = textContent(paragraph ?: caption)
            if (text.isNotBlank()) return text
        }
        val clamp = nodes.firstOrNull { it.classNames().any { name -> name.startsWith("b_lineclamp") } }
        if (clamp != null && textContent(clamp).isNotBlank()) return textContent(clamp)
        return nodes.firstOrNull { it.hasClass("b_algoSlug") }?.let(::textContent).orEmpty()
    }

    private fun HtmlElement.classNames() = attr("class").orEmpty().split(Regex("\\s+")).filter(String::isNotBlank)

    private fun isAccessGate(title: String, visibleText: String, document: HtmlElement): Boolean {
        val normalizedTitle = title.lowercase(Locale.ROOT)
        val identifiers = descendants(document).flatMap { listOf(it.attr("id").orEmpty(), it.attr("class").orEmpty()) }
            .joinToString(" ").lowercase(Locale.ROOT)
        return normalizedTitle.contains("captcha") || normalizedTitle.contains("consent") || normalizedTitle.contains("robot") ||
            visibleText.contains("our systems have detected unusual traffic") ||
            visibleText.contains("verify you are human") || identifiers.contains("captcha-delivery") ||
            identifiers.contains("b_captcha") || visibleText.contains("consent.microsoft.com")
    }

    private fun readTree(html: String): HtmlElement {
        val root = HtmlElement("#document", emptyMap(), null)
        val open = mutableListOf(root)
        var cursor = 0
        while (cursor < html.length) {
            val tagStart = html.indexOf('<', cursor)
            if (tagStart < 0) {
                addText(open.last(), html.substring(cursor))
                break
            }
            if (tagStart > cursor) addText(open.last(), html.substring(cursor, tagStart))
            if (html.startsWith("<!--", tagStart)) {
                cursor = (html.indexOf("-->", tagStart + 4).takeIf { it >= 0 } ?: html.length - 3) + 3
                continue
            }
            val end = tagEnd(html, tagStart + 1)
            if (end < 0) {
                addText(open.last(), html.substring(tagStart))
                break
            }
            val inside = html.substring(tagStart + 1, end).trim()
            cursor = end + 1
            if (inside.isEmpty() || inside[0] == '!' || inside[0] == '?') continue
            val closing = inside.startsWith('/')
            val nameStart = if (closing) 1 else 0
            var nameEnd = nameStart
            while (nameEnd < inside.length && !inside[nameEnd].isWhitespace() && inside[nameEnd] != '/') nameEnd++
            val tag = inside.substring(nameStart, nameEnd).lowercase(Locale.ROOT)
            if (tag.isEmpty()) continue
            if (closing) {
                val matching = open.indexOfLast { it.name == tag }
                if (matching > 0) while (open.size > matching) open.removeAt(open.lastIndex)
                continue
            }

            val node = HtmlElement(tag, readAttributes(inside.substring(nameEnd).trim()), open.last())
            open.last().children += node
            if (tag in voidElements || inside.trimEnd().endsWith('/')) continue
            if (tag == "script" || tag == "style") {
                val closingStart = html.indexOf("</$tag", cursor, ignoreCase = true)
                if (closingStart >= 0) {
                    val closingEnd = tagEnd(html, closingStart + 2 + tag.length)
                    cursor = if (closingEnd >= 0) closingEnd + 1 else html.length
                } else cursor = html.length
                continue
            }
            if (open.size < MAX_HTML_NESTING) open += node
        }
        return root
    }

    private fun addText(parent: HtmlElement, value: String) {
        if (value.isNotEmpty()) parent.children += HtmlText(value)
    }

    private fun tagEnd(source: String, start: Int): Int {
        var quote = '\u0000'
        for (position in start until source.length) {
            val character = source[position]
            if (quote != '\u0000') {
                if (character == quote) quote = '\u0000'
            } else when (character) {
                '\'', '"' -> quote = character
                '>' -> return position
            }
        }
        return -1
    }

    private fun readAttributes(source: String): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        var position = 0
        while (position < source.length) {
            while (position < source.length && (source[position].isWhitespace() || source[position] == '/')) position++
            if (position >= source.length) break
            val nameStart = position
            while (position < source.length && !source[position].isWhitespace() && source[position] !in "=/>") position++
            if (position == nameStart) { position++; continue }
            val name = source.substring(nameStart, position).lowercase(Locale.ROOT)
            while (position < source.length && source[position].isWhitespace()) position++
            var value = ""
            if (position < source.length && source[position] == '=') {
                position++
                while (position < source.length && source[position].isWhitespace()) position++
                if (position < source.length && source[position] in "\"'") {
                    val quote = source[position++]
                    val valueStart = position
                    while (position < source.length && source[position] != quote) position++
                    value = source.substring(valueStart, position)
                    if (position < source.length) position++
                } else {
                    val valueStart = position
                    while (position < source.length && !source[position].isWhitespace() && source[position] != '>') position++
                    value = source.substring(valueStart, position)
                }
            }
            attributes.putIfAbsent(name, htmlDecode(value))
        }
        return attributes
    }

    private fun descendants(root: HtmlElement): List<HtmlElement> {
        val result = ArrayList<HtmlElement>()
        val pending = ArrayDeque<HtmlElement>()
        root.children.filterIsInstance<HtmlElement>().asReversed().forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            result += current
            current.children.filterIsInstance<HtmlElement>().asReversed().forEach(pending::addLast)
        }
        return result
    }

    private fun textContent(element: HtmlElement?): String {
        if (element == null) return ""
        val output = StringBuilder()
        fun appendContent(part: HtmlPart) {
            when (part) {
                is HtmlText -> output.append(htmlDecode(part.value)).append(' ')
                is HtmlElement -> {
                    if (part.name == "script" || part.name == "style" || part.name == "noscript") return
                    if (part.name in blockElements) output.append(' ')
                    part.children.forEach(::appendContent)
                    if (part.name in blockElements) output.append(' ')
                }
            }
        }
        element.children.forEach(::appendContent)
        return output.toString().replace(Regex("[\\p{Z}\\s]+"), " ").trim()
    }

    private const val MAX_HTML_NESTING = 256
}

/** URL canonicalization and Bing ck/a redirect decoding; only ordinary web schemes are returned. */
object WebSearchUrlPolicy {
    private val base64UrlChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decodeBingRedirect(raw: String): String? {
        var candidate = raw.trim()
        if (candidate.startsWith("//")) candidate = "https:$candidate"
        val explicitScheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(candidate)?.groupValues?.get(1)
        if (explicitScheme != null && !explicitScheme.equals("http", true) && !explicitScheme.equals("https", true)) return null
        if (explicitScheme == null) candidate = "https://$candidate"
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
        if ((host == "bing.com" || host.endsWith(".bing.com")) && parsed.path.startsWith("/ck/a")) {
            val encoded = parsed.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("u=", true) }
                ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
            if (encoded.startsWith("a1") && encoded.length > 2) {
                val decoded = decodeBase64Url(encoded.substring(2)) ?: return null
                candidate = decoded
            }
        }
        if (candidate.startsWith("//")) candidate = "https:$candidate"
        val result = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!(result.scheme.equals("http", true) || result.scheme.equals("https", true))) return null
        if (result.host.isNullOrBlank() || result.userInfo != null || result.rawUserInfo != null) return null
        return result.toASCIIString()
    }

    fun decodePublicResultUrl(raw: String): String? {
        val safe = decodeBingRedirect(raw) ?: return null
        val uri = runCatching { URI(safe) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host == "bing.com" || host.endsWith(".bing.com") || host == "localhost" ||
            host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null
        if (host.contains(':')) return null
        if (host.matches(Regex("[0-9.]+")) && !host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) return null
        if (host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) {
            val octets = host.split('.').map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            val (a, b, c) = octets
            if (a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && (b == 168 || b == 0 || b == 2)) || (a == 100 && b in 64..127) ||
                (a == 198 && (b in 18..19 || b == 51 && c == 100)) || (a == 203 && b == 0 && c == 113) || a >= 224) return null
        }
        return safe
    }

    private fun decodeBase64Url(value: String): String? {
        var accumulator = 0
        var bits = 0
        val output = ByteArrayOutputStream()
        for (ch in value.trimEnd('=')) {
            val digit = base64UrlChars.indexOf(ch)
            if (digit < 0) return null
            accumulator = (accumulator shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((accumulator ushr bits) and 0xff)
            }
        }
        return runCatching { String(output.toByteArray(), StandardCharsets.UTF_8) }.getOrNull()
    }
}

/** Local read-only HTML fetch with public-address checks before every request/redirect. */
class WebPageFetcher(
    private val transport: WebHttpTransport = UrlConnectionWebHttpTransport,
    private val dnsResolver: WebDnsResolver = SystemWebDnsResolver,
) {
    fun fetch(rawUrl: String, acceptLanguage: String, maxChars: Int = MAX_TEXT_CHARS): WebPageOutcome {
        if (maxChars !in 1..MAX_TEXT_CHARS) return WebPageOutcome("", "", "", false, "url_policy", "max_chars is outside the allowed range")
        var current = WebUrlSafety.normalize(rawUrl).getOrElse { return WebPageOutcome("", "", "", false, "url_policy", it.message) }
        return try {
            for (redirectCount in 0..MAX_REDIRECTS) {
                WebUrlSafety.ensurePublicHost(current.host, dnsResolver)
                val response = transport.get(current.toASCIIString(), mapOf(
                    "User-Agent" to BingLocalSearchProvider.MOBILE_USER_AGENT,
                    "Accept-Language" to BingLocalSearchProvider.safeAcceptLanguage(acceptLanguage),
                    "Accept" to ACCEPTED_TYPES,
                ), TIMEOUT_MS, MAX_PAGE_BYTES, followRedirects = false)
                if (response.status in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) return WebPageOutcome(current.toASCIIString(), "", "", false,
                        "redirect_limit", "The page redirected more than $MAX_REDIRECTS times")
                    val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value
                        ?: return WebPageOutcome(current.toASCIIString(), "", "", false, "redirect_invalid", "Redirect response had no Location")
                    current = WebUrlSafety.normalize(current.resolve(location).toString()).getOrElse {
                        return WebPageOutcome(current.toASCIIString(), "", "", false, "url_policy", it.message)
                    }
                    continue
                }
                if (response.status !in 200..299) return WebPageOutcome(current.toASCIIString(), "", "", false,
                    "http_${response.status}", "Page returned HTTP ${response.status}")
                val mime = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
                    ?.value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (mime !in ACCEPTED_MIME_TYPES) return WebPageOutcome(current.toASCIIString(), "", "", false,
                    "content_type", "Only HTML, plain text, JSON and XML pages can be fetched")
                val body = String(response.body, StandardCharsets.UTF_8)
                val title = Regex("(?is)<title\\b[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)
                    ?.let(BingHtmlParser::cleanHtml).orEmpty().take(300)
                val text = if (mime.contains("html")) WebPageText.extract(body) else body
                    .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                if (shouldUseReaderFallback(mime, text)) {
                    val reader = fetchViaJina(current, acceptLanguage, maxChars)
                    if (reader != null) return reader
                }
                val truncation = response.truncated || text.length > maxChars
                val bounded = text.take(maxChars) + if (truncation) "\n[Truncated at $maxChars characters.]" else ""
                return WebPageOutcome(current.toASCIIString(), title, bounded, truncation)
            }
            WebPageOutcome(current.toASCIIString(), "", "", false, "redirect_limit", "Redirect limit reached")
        } catch (_: java.net.SocketTimeoutException) {
            WebPageOutcome(current.toASCIIString(), "", "", false, "timeout", "Page request timed out")
        } catch (_: TimeoutException) {
            WebPageOutcome(current.toASCIIString(), "", "", false, "timeout", "DNS lookup timed out")
        } catch (_: UnknownHostException) {
            WebPageOutcome(current.toASCIIString(), "", "", false, "network", "Page host could not be resolved")
        } catch (blocked: IllegalArgumentException) {
            WebPageOutcome(current.toASCIIString(), "", "", false, "url_policy", blocked.message ?: "URL was blocked by policy")
        } catch (error: Exception) {
            WebPageOutcome(current.toASCIIString(), "", "", false, "network", error.message?.take(200) ?: "Page request failed")
        }
    }

    private fun fetchViaJina(pageUri: URI, acceptLanguage: String, maxChars: Int): WebPageOutcome? {
        return try {
            val encodedUrl = pageUri.toASCIIString()
            val response = transport.get("https://r.jina.ai/$encodedUrl", mapOf(
                "User-Agent" to "Jarvys/1.0", "Accept" to "text/plain", "Accept-Language" to BingLocalSearchProvider.safeAcceptLanguage(acceptLanguage),
            ), TIMEOUT_MS, MAX_PAGE_BYTES, followRedirects = false)
            if (response.status !in 200..299 || response.truncated) null else {
                val text = String(response.body, StandardCharsets.UTF_8).replace(Regex("\\s+"), " ").trim()
                if (text.isBlank() || looksLikeJavascriptShell(text)) null else {
                    val marker = "[Retrieved via Jina Reader fallback.]"
                    val contentLimit = (maxChars - marker.length - 1).coerceAtLeast(0)
                    val truncated = text.length > contentLimit
                    WebPageOutcome(pageUri.toASCIIString(), "", text.take(contentLimit) + "\n" + marker, truncated)
                }
            }
        } catch (_: Exception) { null }
    }

    private fun shouldUseReaderFallback(mime: String, text: String): Boolean = mime.contains("html") && (text.isBlank() || looksLikeJavascriptShell(text))

    private fun looksLikeJavascriptShell(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return normalized.length < 500 && (normalized.contains("enable javascript") || normalized.contains("javascript is required") ||
            normalized.contains("you need to enable javascript") || normalized.contains("please wait while") || normalized.contains("checking your browser"))
    }

    companion object {
        const val MAX_TEXT_CHARS = 25_000
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val TIMEOUT_MS = 20_000
        const val ACCEPTED_TYPES = "text/html,application/xhtml+xml,text/plain,application/json,application/xml,text/xml"
        val ACCEPTED_MIME_TYPES = setOf("text/html", "application/xhtml+xml", "text/plain", "application/json", "application/xml", "text/xml")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal object WebPageText {
    fun extract(html: String): String = html
        .replace(Regex("(?is)<(script|style|nav|header|footer|aside)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        .replace(Regex("(?is)<[^>]*>"), " ")
        .let(BingHtmlParser::htmlDecode)
        .replace(Regex("[\\p{Z}\\s]+"), " ").trim()
}

object WebUrlSafety {
    private val dnsExecutor = Executors.newCachedThreadPool { Thread(it, "JarvysWebSearchDNS").apply { isDaemon = true } }

    fun normalize(raw: String): Result<URI> = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Only http and https URLs are allowed" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "URL host or authority is invalid" }
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal")) {
            "Local and internal hosts are not allowed"
        }
        uri
    }

    fun ensurePublicHost(host: String, resolver: WebDnsResolver) {
        val addresses = try {
            dnsExecutor.submit<List<InetAddress>> { resolver.resolve(host) }.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause ?: e)
        }
        require(addresses.isNotEmpty() && addresses.none(::isPrivateAddress)) { "Private, loopback, link-local or reserved IP addresses are blocked" }
    }

    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff; val b = bytes[1].toInt() and 0xff
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31)
                || (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 198 && b in 18..19) || a >= 224
        }
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            if (first == 0xfc || first == 0xfd) return true // IPv6 unique-local fc00::/7
            val ipv4Mapped = (0 until 10).all { bytes[it].toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
            if (ipv4Mapped) return isPrivateAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return false
    }

    private const val DNS_TIMEOUT_MS = 3_000L
}

internal object UrlConnectionWebHttpTransport : WebHttpTransport {
    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Int,
                     followRedirects: Boolean): WebHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = followRedirects
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.filterKeys { it != null }
                .mapKeys { it.key.orEmpty() }
                .mapValues { it.value.joinToString(",") }
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            var truncated = false
            input?.use { stream ->
                val buffer = ByteArray(8192)
                while (output.size() <= maxBytes) {
                    val read = stream.read(buffer, 0, min(buffer.size, maxBytes + 1 - output.size()))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > maxBytes) { truncated = true; break }
                }
            }
            val bytes = output.toByteArray()
            return WebHttpResponse(status, responseHeaders, if (truncated) bytes.copyOf(maxBytes) else bytes, truncated)
        } finally { connection.disconnect() }
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, maxBytes: Int): WebHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = timeoutMs; connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false; connection.useCaches = false; connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.orEmpty() }.mapValues { it.value.joinToString(",") }
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream(); var truncated = false
            input?.use { stream -> val buffer = ByteArray(8192); while (output.size() <= maxBytes) {
                val count = stream.read(buffer, 0, min(buffer.size, maxBytes + 1 - output.size())); if (count < 0) break
                output.write(buffer, 0, count); if (output.size() > maxBytes) { truncated = true; break }
            } }
            val bytes = output.toByteArray()
            return WebHttpResponse(status, responseHeaders, if (truncated) bytes.copyOf(maxBytes) else bytes, truncated)
        } finally { connection.disconnect() }
    }
}

private fun safeMessage(error: Exception) = error.message?.take(200) ?: "Bing search request failed"
