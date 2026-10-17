package com.jarvys.agent.modules

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ModuleToolAccess { READ, WRITE }

data class ModuleArtifactType(
    val type: String,
    val viewer: String? = null,
    val generatedThumbnail: Boolean = false,
)

data class ModuleToolDeclaration(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val access: ModuleToolAccess,
    val handler: String,
)

/** A parsed, validated manifest. The source JSON is retained as opaque artifact metadata. */
data class ModuleManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val name: String,
    val artifactTypes: List<ModuleArtifactType>,
    val tools: List<ModuleToolDeclaration>,
    val skills: List<String>,
    val capabilities: Set<String>,
    val manifestJson: String,
    /** Asset directory that owns this manifest; assigned by the bundled loader, not the manifest. */
    val bundleAssetDirectory: String = id,
)

/** Parsing and schema validation for manifests shipped with this application. */
object ModuleManifestParser {
    fun parse(json: String, bundleAssetDirectory: String? = null): ModuleManifest {
        val root = try {
            JSONObject(json)
        } catch (invalid: Exception) {
            throw IllegalArgumentException("Module manifest must be a JSON object", invalid)
        }
        require(root.optInt("schemaVersion", -1) == 1) { "Unsupported module manifest schemaVersion" }
        val id = requiredString(root, "id")
        require(MODULE_ID.matches(id)) { "Module id is invalid" }
        val version = requiredString(root, "version")
        val name = requiredString(root, "name")

        val artifactTypes = parseArtifactTypes(root.optJSONArray("artifactTypes") ?: JSONArray())
        val tools = parseTools(root.optJSONArray("tools") ?: JSONArray())
        val skills = parseStrings(root.optJSONArray("skills") ?: JSONArray(), "skills").onEach(::requireBundlePath)
        val capabilities = parseStrings(root.optJSONArray("capabilities") ?: JSONArray(), "capabilities")
            .onEach { require(CAPABILITY.matches(it)) { "Module capability name is invalid" } }
            .toCollection(linkedSetOf())
        require(capabilities.all { it in ModuleCapabilities.supported }) { "Module declares an unsupported host capability" }
        val assetDirectory = bundleAssetDirectory ?: id
        requireBundlePath(assetDirectory)

        require(tools.map { it.name }.distinct().size == tools.size) { "Module tool names must be unique" }
        require(artifactTypes.map { canonicalType(it.type) }.distinct().size == artifactTypes.size) {
            "Module artifact type declarations must be unique"
        }

        return ModuleManifest(
            schemaVersion = 1,
            id = id,
            version = version,
            name = name,
            artifactTypes = artifactTypes,
            tools = tools,
            skills = skills,
            capabilities = capabilities,
            manifestJson = json,
            bundleAssetDirectory = assetDirectory,
        )
    }

    private fun parseArtifactTypes(values: JSONArray): List<ModuleArtifactType> = (0 until values.length()).map { index ->
        val value = values.opt(index) as? JSONObject
            ?: throw IllegalArgumentException("artifactTypes[$index] must be an object")
        val type = requiredString(value, "type")
        val canonicalType = canonicalType(type)
        require(MIME_PATTERN.matches(canonicalType) && (!canonicalType.startsWith("*") || canonicalType == "*/*")) {
            "Module artifact type is invalid"
        }
        require(!value.has("thumbnail")) {
            "Module thumbnail permission must use generatedThumbnail instead of a bundle path"
        }
        val viewer = optionalString(value, "viewer")?.also(::requireBundlePath)
        val generatedThumbnail = optionalBoolean(value, "generatedThumbnail")
        ModuleArtifactType(type, viewer, generatedThumbnail)
    }

    private fun parseTools(values: JSONArray): List<ModuleToolDeclaration> = (0 until values.length()).map { index ->
        val value = values.opt(index) as? JSONObject
            ?: throw IllegalArgumentException("tools[$index] must be an object")
        val name = requiredString(value, "name")
        require(TOOL_NAME.matches(name)) { "Module tool name is invalid" }
        val description = requiredString(value, "description")
        val schema = value.optJSONObject("inputSchema")
            ?: throw IllegalArgumentException("Module tool '$name' requires an inputSchema object")
        validateSchema(schema, "tools[$index].inputSchema", root = true)
        val access = when (requiredString(value, "access")) {
            "read" -> ModuleToolAccess.READ
            "write" -> ModuleToolAccess.WRITE
            else -> throw IllegalArgumentException("Module tool '$name' access must be read or write")
        }
        val handler = requiredString(value, "handler")
        val handlerParts = handler.split('#')
        require(handlerParts.size == 2 && HANDLER_NAME.matches(handlerParts[1])) {
            "Module tool '$name' handler must be a bundle path and export name"
        }
        requireBundlePath(handlerParts[0])
        ModuleToolDeclaration(name, description, jsonObjectToMap(schema), access, handler)
    }

    private fun validateSchema(schema: JSONObject, path: String, root: Boolean = false) {
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key in SCHEMA_KEYWORDS) { "$path contains an unsupported JSON Schema keyword '$key'" }
        }
        val typeValue = schema.opt("type")
        if (root) require(typeValue == "object") { "$path.type must be object" }
        if (typeValue != null) {
            val types = when (typeValue) {
                is String -> listOf(typeValue)
                is JSONArray -> (0 until typeValue.length()).map { typeValue.optString(it) }
                else -> throw IllegalArgumentException("$path.type must be a string or array")
            }
            require(types.isNotEmpty() && types.all { it in JSON_TYPES }) { "$path.type contains an invalid JSON Schema type" }
        }
        schema.optJSONObject("properties")?.let { properties ->
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = properties.optJSONObject(key)
                    ?: throw IllegalArgumentException("$path.properties.$key must be a schema object")
                validateSchema(child, "$path.properties.$key")
            }
        }
        schema.opt("required")?.let { required ->
            val names = required as? JSONArray
                ?: throw IllegalArgumentException("$path.required must be an array")
            val strings = parseStrings(names, "$path.required")
            require(strings.distinct().size == strings.size) { "$path.required must not contain duplicates" }
        }
        schema.opt("items")?.let { items ->
            val itemSchema = items as? JSONObject
                ?: throw IllegalArgumentException("$path.items must be a schema object")
            validateSchema(itemSchema, "$path.items")
        }
        schema.opt("additionalProperties")?.let { additional ->
            if (additional is JSONObject) validateSchema(additional, "$path.additionalProperties")
            else require(additional is Boolean) { "$path.additionalProperties must be a boolean or schema object" }
        }
        schema.opt("enum")?.let { values ->
            val alternatives = values as? JSONArray
                ?: throw IllegalArgumentException("$path.enum must be an array")
            require(alternatives.length() > 0) { "$path.enum must not be empty" }
        }
        schema.opt("anyOf")?.let { options ->
            val alternatives = options as? JSONArray
                ?: throw IllegalArgumentException("$path.anyOf must be an array")
            require(alternatives.length() > 0) { "$path.anyOf must not be empty" }
            for (index in 0 until alternatives.length()) {
                val option = alternatives.optJSONObject(index)
                    ?: throw IllegalArgumentException("$path.anyOf[$index] must be a schema object")
                validateSchema(option, "$path.anyOf[$index]")
            }
        }
    }

    private fun parseStrings(values: JSONArray, field: String): List<String> = (0 until values.length()).map { index ->
        val value = values.opt(index) as? String
            ?: throw IllegalArgumentException("$field[$index] must be a string")
        require(value.isNotBlank() && value.none(Char::isISOControl)) { "$field[$index] must not be blank" }
        value
    }

    private fun requiredString(value: JSONObject, key: String): String =
        (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: throw IllegalArgumentException("Module manifest field '$key' is required")

    private fun optionalString(value: JSONObject, key: String): String? = when {
        !value.has(key) || value.isNull(key) -> null
        value.opt(key) is String -> value.getString(key)
        else -> throw IllegalArgumentException("Module manifest field '$key' must be a string")
    }

    private fun optionalBoolean(value: JSONObject, key: String): Boolean = when {
        !value.has(key) -> false
        value.opt(key) is Boolean -> value.getBoolean(key)
        else -> throw IllegalArgumentException("Module manifest field '$key' must be a boolean")
    }

    private fun requireBundlePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            '%' !in path && '?' !in path && '#' !in path && path.none(Char::isISOControl) &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "Module bundle path must stay inside the module"
        }
    }

    private fun canonicalType(type: String) = type.substringBefore(';').trim().lowercase(Locale.ROOT)

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, jsonValue(json.opt(key)))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        else -> value
    }

    private val JSON_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val SCHEMA_KEYWORDS = setOf(
        "type", "properties", "required", "items", "additionalProperties", "anyOf", "enum", "description", "title",
    )
    private val MODULE_ID = Regex("[A-Za-z][A-Za-z0-9._-]*")
    private val TOOL_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val HANDLER_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val CAPABILITY = Regex("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)*")
    private val MIME_PATTERN = Regex("(?:\\*|[a-z0-9][a-z0-9!#$&^_.+-]*)/(?:\\*|[a-z0-9][a-z0-9!#$&^_.+-]*)")
}
