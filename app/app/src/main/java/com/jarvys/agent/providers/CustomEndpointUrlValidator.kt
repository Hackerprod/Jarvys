package com.jarvys.agent.providers

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

data class NormalizedCustomEndpoint(
    val baseUrl: String,
    val chatCompletionsUrl: String,
    val modelsUrl: String,
    val host: String,
)

fun interface CustomEndpointAddressResolver {
    fun resolve(host: String): List<InetAddress>
}

class CustomEndpointUrlException : IllegalArgumentException("Custom endpoint URL is not a safe public HTTPS URL")

/** Pure scheme/host checks with an injectable DNS check for every outbound request. */
class CustomEndpointUrlValidator(
    private val addressResolver: CustomEndpointAddressResolver = CustomEndpointAddressResolver { host ->
        InetAddress.getAllByName(host).toList()
    },
) {
    fun normalizeBaseUrl(input: String): NormalizedCustomEndpoint {
        val uri = try { URI(input.trim()) } catch (_: Exception) { throw CustomEndpointUrlException() }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null
            || uri.rawFragment != null || uri.rawQuery != null) throw CustomEndpointUrlException()
        val host = uri.host?.trim('[', ']')?.trimEnd('.')?.lowercase() ?: throw CustomEndpointUrlException()
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost")
            || host.endsWith(".local") || host.endsWith(".internal") || '%' in host) {
            throw CustomEndpointUrlException()
        }
        literalAddress(host)?.let { if (isNonPublicAddress(it)) throw CustomEndpointUrlException() }

        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.endsWith("/chat/completions")) path = path.removeSuffix("/chat/completions").trimEnd('/')
        val authority = uri.rawAuthority?.takeIf(String::isNotBlank) ?: throw CustomEndpointUrlException()
        return NormalizedCustomEndpoint(
            baseUrl = "https://$authority$path",
            chatCompletionsUrl = "https://$authority$path/chat/completions",
            modelsUrl = "https://$authority$path/models",
            host = host,
        )
    }

    fun validateAndResolveBaseUrl(input: String): NormalizedCustomEndpoint {
        val normalized = normalizeBaseUrl(input)
        val addresses = try { addressResolver.resolve(normalized.host) }
            catch (_: Exception) { throw CustomEndpointUrlException() }
        if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) throw CustomEndpointUrlException()
        return normalized
    }

    fun validateChatCompletionsUrl(endpoint: String) {
        val normalized = normalizeBaseUrl(endpoint)
        if (normalized.chatCompletionsUrl != endpoint) throw CustomEndpointUrlException()
        validateAndResolveBaseUrl(normalized.baseUrl)
    }

    private fun literalAddress(host: String): InetAddress? {
        if (':' in host) {
            return try { InetAddress.getByName(host) } catch (_: Exception) { throw CustomEndpointUrlException() }
        }
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { octet -> octet.isEmpty() || !octet.all { it in '0'..'9' } }) return null
        val values = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: throw CustomEndpointUrlException() }
        return try { InetAddress.getByAddress(values.map(Int::toByte).toByteArray()) }
            catch (_: Exception) { throw CustomEndpointUrlException() }
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
            || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address || bytes.size == 16 && bytes.take(10).all { it == 0 }
            && bytes[10] == 0xff && bytes[11] == 0xff) {
            val v4 = if (bytes.size == 4) bytes else bytes.takeLast(4)
            val a = v4[0]
            val b = v4[1]
            val c = v4[2]
            val d = v4[3]
            return a == 0 || a == 10 || a == 127 || a >= 224
                || a == 169 && b == 254
                || a == 172 && b in 16..31
                || a == 192 && (b == 168 || b == 0 && c in setOf(0, 2) || b == 88 && c == 99)
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113
                || a == 168 && b == 63 && c == 129 && d == 16
                || a == 100 && b in 64..127
        }
        if (address is Inet6Address) {
            val first = bytes[0]
            val second = bytes[1]
            return (first and 0xfe) == 0xfc // Unique-local (fc00::/7)
                || first == 0xfe && (second and 0xc0) == 0x80 // Link-local (fe80::/10)
                || first == 0xff // Multicast (ff00::/8)
                || first == 0x20 && second == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8 // Documentation
                || first == 0x20 && second == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00 // Teredo
        }
        return false
    }
}
