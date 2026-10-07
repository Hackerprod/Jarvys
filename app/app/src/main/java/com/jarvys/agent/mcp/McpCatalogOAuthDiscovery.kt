package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.RemoteServiceCatalog
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Catalog-scoped OAuth behavior; custom MCP servers continue to use only their discovered hints. */
internal object McpCatalogOAuthDiscovery {
    fun requestedScope(serviceId: String?, challenge: String, protectedResource: JSONObject): String? {
        val catalogScopes = RemoteServiceCatalog.find(serviceId)?.requestedScopes
        if (catalogScopes != null) return catalogScopes.sorted().joinToString(" ").takeIf(String::isNotBlank)
        val challengeScope = Regex("scope\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(challenge)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (challengeScope.isNotBlank()) return challengeScope
        val advertised = protectedResource.optJSONArray("scopes_supported") ?: return null
        return (0 until advertised.length()).map { advertised.optString(it) }
            .filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank)
    }

    /** Atlassian publishes AS metadata at same-origin but returned 404 for every PRM candidate. */
    fun directAuthorizationServerMetadata(
        serviceId: String?,
        protectedResourceUri: URI,
        fetchJson: (String) -> JSONObject,
    ): JSONObject? {
        val metadataUrl = RemoteServiceCatalog.find(serviceId)?.authorizationServerMetadataUrl ?: return null
        val metadataUri = runCatching { URI(metadataUrl) }.getOrNull() ?: error("Catalog OAuth metadata URL is invalid")
        require(isHttps(metadataUri) && sameOrigin(protectedResourceUri, metadataUri)) {
            "Catalog OAuth metadata fallback must be HTTPS and share the MCP endpoint origin"
        }
        val metadata = fetchJson(metadataUri.toString())
        val issuer = runCatching { URI(metadata.optString("issuer")) }.getOrNull()
            ?: error("Direct OAuth authorization-server metadata omitted issuer")
        require(isHttps(issuer) && sameOrigin(protectedResourceUri, issuer)) {
            "Direct OAuth authorization-server issuer does not match the catalog endpoint origin"
        }
        require(metadata.has("authorization_endpoint") && metadata.has("token_endpoint")) {
            "Direct OAuth authorization-server metadata omitted required endpoints"
        }
        return metadata
    }

    private fun isHttps(uri: URI) = uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
        && uri.userInfo == null && uri.fragment == null

    private fun sameOrigin(left: URI, right: URI) = left.scheme.equals(right.scheme, true)
        && left.host.equals(right.host, true) && port(left) == port(right)

    private fun port(uri: URI) = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.lowercase(Locale.ROOT) == "https") 443 else 80
}
