package com.jarvys.agent

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

data class ModelVariant(
    val id: String,
    val reasoningEffort: String,
    val reasoningSummary: String,
    val textVerbosity: String,
)

data class ModelInfo(
    val id: String,
    val name: String,
    val contextLimit: Int,
    val outputLimit: Int,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val variants: List<ModelVariant>,
) {
    val defaultVariant: ModelVariant
        get() = variants.firstOrNull { it.id == "medium" }
            ?: variants.firstOrNull()
            ?: ModelVariant("medium", "medium", "auto", "medium")
}

data class OpenRouterModelInfo(
    val id: String,
    val name: String,
    val contextLimit: Int,
    val outputLimit: Int,
    val inputModalities: Set<String> = emptySet(),
)

/** OpenCode-compatible OpenAI OAuth model filter backed by the public models.dev catalog. */
object CodexModelCatalog {
    const val CATALOG_URL = "https://models.dev/api.json"
    private const val PREFS = "codex_models_dev_cache"
    private const val CACHE_KEY = "catalog_json_r1b"
    private const val OPENROUTER_CACHE_KEY = "openrouter_catalog_json"
    private const val OPENAI_API_CACHE_KEY = "openai_api_catalog_json"
    private const val FETCHED_KEY = "fetched_at"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_CATALOG_BYTES = 12 * 1024 * 1024
    private val ALLOW = setOf("gpt-5.5", "gpt-5.3-codex-spark", "gpt-5.4", "gpt-5.4-mini")
    private val DENY = setOf("gpt-5.5-pro")
    private val modelPattern = Pattern.compile("^gpt-(\\d+)(?:\\.(\\d+))?")
    private val refreshExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysModelCatalog").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var authoritativeCatalogLoaded = false
    @Volatile private var authoritativeOpenAiApiCatalogLoaded = false

    private val _models = MutableStateFlow(fallbackModels())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()
    private val _openRouterModels = MutableStateFlow<List<OpenRouterModelInfo>>(emptyList())
    val openRouterModels: StateFlow<List<OpenRouterModelInfo>> = _openRouterModels.asStateFlow()
    private val _openAiApiModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val openAiApiModels: StateFlow<List<ModelInfo>> = _openAiApiModels.asStateFlow()

    @JvmStatic
    fun currentModels(): List<ModelInfo> = _models.value

    /** Unknown or empty metadata remains unknown; fallback UI rows never block attachment vision. */
    @JvmStatic
    fun visionSupport(provider: ProviderSettings.Provider, modelId: String): Boolean? {
        val modalities = when (provider) {
            ProviderSettings.Provider.OPENAI_CODEX -> if (authoritativeCatalogLoaded) find(modelId)?.inputModalities else null
            ProviderSettings.Provider.OPENAI_API -> if (authoritativeOpenAiApiCatalogLoaded) {
                _openAiApiModels.value.firstOrNull { it.id == modelId }?.inputModalities
            } else null
            ProviderSettings.Provider.OPENROUTER -> _openRouterModels.value.firstOrNull { it.id == modelId }?.inputModalities
            ProviderSettings.Provider.CUSTOM -> null
        }
        return modalities?.takeIf { it.isNotEmpty() }?.contains("image")
    }

    /** Loads a valid cache immediately, then refreshes stale/missing data away from the UI thread. */
    @JvmStatic
    fun loadCached(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = prefs.getString(CACHE_KEY, null)?.let(::parseStoredCatalog)
            val cachedOpenRouter = prefs.getString(OPENROUTER_CACHE_KEY, null)?.let(::parseStoredOpenRouterCatalog)
            val cachedOpenAiApi = prefs.getString(OPENAI_API_CACHE_KEY, null)?.let(::parseStoredCatalog)
            if (!cached.isNullOrEmpty()) {
                _models.value = cached
                authoritativeCatalogLoaded = true
            }
            if (!cachedOpenRouter.isNullOrEmpty()) _openRouterModels.value = cachedOpenRouter
            if (!cachedOpenAiApi.isNullOrEmpty()) {
                _openAiApiModels.value = cachedOpenAiApi
                authoritativeOpenAiApiCatalogLoaded = true
            }
            if (cached.isNullOrEmpty() || cachedOpenRouter.isNullOrEmpty() || cachedOpenAiApi.isNullOrEmpty()
                || System.currentTimeMillis() - prefs.getLong(FETCHED_KEY, 0L) >= CACHE_TTL_MS) {
                refreshAsync(context.applicationContext)
            }
        }
    }

    @JvmStatic
    fun refreshAsync(context: Context) {
        val appContext = context.applicationContext
        refreshExecutor.execute {
            runCatching {
                val root = fetchCatalogPayload(null)
                val parsed = parseModelsDev(root)
                require(parsed.isNotEmpty()) { "models.dev returned no OpenAI Codex models" }
                val parsedOpenRouter = parseOpenRouterModelsDev(root)
                val parsedOpenAiApi = parseOpenAiApiModelsDev(root)
                val json = serializeCatalog(parsed).toString()
                val openRouterJson = serializeOpenRouterCatalog(parsedOpenRouter).toString()
                val openAiApiJson = serializeCatalog(parsedOpenAiApi).toString()
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CACHE_KEY, json)
                    .putString(OPENROUTER_CACHE_KEY, openRouterJson)
                    .putString(OPENAI_API_CACHE_KEY, openAiApiJson)
                    .putLong(FETCHED_KEY, System.currentTimeMillis())
                    .apply()
                _models.value = parsed
                _openRouterModels.value = parsedOpenRouter
                _openAiApiModels.value = parsedOpenAiApi
                authoritativeCatalogLoaded = true
                authoritativeOpenAiApiCatalogLoaded = parsedOpenAiApi.isNotEmpty()
            }
        }
    }

    @JvmStatic
    fun find(modelId: String): ModelInfo? = _models.value.firstOrNull { it.id == modelId }

    /** Returns only metadata loaded from models.dev, never the UI's conservative fallback rows. */
    @JvmStatic
    fun authoritativeContextLimit(modelId: String): Int? = if (authoritativeCatalogLoaded) {
        find(modelId)?.contextLimit?.takeIf { it > 0 }
    } else null

    @JvmStatic
    fun openAiApiOutputLimit(modelId: String): Int? = if (authoritativeOpenAiApiCatalogLoaded) {
        _openAiApiModels.value.firstOrNull { it.id == modelId }?.outputLimit?.takeIf { it > 0 }
    } else null

    @JvmStatic
    fun contextLimitForRun(context: Context, modelId: String, token: CancellationToken): Int? {
        authoritativeContextLimit(modelId)?.let { return it }
        token.throwIfCancelled()
        return try {
            val root = fetchCatalogPayload(token)
            val parsed = parseModelsDev(root)
            token.throwIfCancelled()
            if (parsed.isNotEmpty()) {
                val openRouter = parseOpenRouterModelsDev(root)
                val openAiApi = parseOpenAiApiModelsDev(root)
                val app = context.applicationContext
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CACHE_KEY, serializeCatalog(parsed).toString())
                    .putString(OPENROUTER_CACHE_KEY, serializeOpenRouterCatalog(openRouter).toString())
                    .putString(OPENAI_API_CACHE_KEY, serializeCatalog(openAiApi).toString())
                    .putLong(FETCHED_KEY, System.currentTimeMillis())
                    .apply()
                _models.value = parsed
                _openRouterModels.value = openRouter
                _openAiApiModels.value = openAiApi
                authoritativeCatalogLoaded = true
                authoritativeOpenAiApiCatalogLoaded = openAiApi.isNotEmpty()
            }
            authoritativeContextLimit(modelId)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            token.throwIfCancelled()
            null
        }
    }

    @JvmStatic
    fun contextLimitForOpenAiApiRun(context: Context, modelId: String, token: CancellationToken): Int? {
        openAiApiContextLimit(modelId)?.let { return it }
        token.throwIfCancelled()
        return try {
            val root = fetchCatalogPayload(token)
            val codex = parseModelsDev(root)
            val openRouter = parseOpenRouterModelsDev(root)
            val openAiApi = parseOpenAiApiModelsDev(root)
            token.throwIfCancelled()
            if (codex.isNotEmpty()) {
                val app = context.applicationContext
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CACHE_KEY, serializeCatalog(codex).toString())
                    .putString(OPENROUTER_CACHE_KEY, serializeOpenRouterCatalog(openRouter).toString())
                    .putString(OPENAI_API_CACHE_KEY, serializeCatalog(openAiApi).toString())
                    .putLong(FETCHED_KEY, System.currentTimeMillis())
                    .apply()
                _models.value = codex
                _openRouterModels.value = openRouter
                _openAiApiModels.value = openAiApi
                authoritativeCatalogLoaded = true
                authoritativeOpenAiApiCatalogLoaded = openAiApi.isNotEmpty()
            }
            openAiApiContextLimit(modelId)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            token.throwIfCancelled()
            null
        }
    }

    private fun openAiApiContextLimit(modelId: String): Int? = if (authoritativeOpenAiApiCatalogLoaded) {
        _openAiApiModels.value.firstOrNull { it.id == modelId }?.contextLimit?.takeIf { it > 0 }
    } else null

    /** Mirrors the order and conditions in OpenCode's OpenAI OAuth models filter. */
    fun shouldInclude(modelId: String, reasoningMode: String? = null): Boolean {
        if (reasoningMode == "pro") return false
        if (modelId in ALLOW) return true
        if (modelId in DENY) return false
        if (modelId == "gpt-5.6") return false
        val match = modelPattern.matcher(modelId)
        if (!match.find()) return false
        val major = match.group(1)?.toIntOrNull() ?: return false
        val minor = match.group(2)?.toIntOrNull() ?: 0
        return major > 5 || major == 5 && minor > 4
    }

    private fun fetchCatalogPayload(token: CancellationToken?): JSONObject {
        val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
        val unregister: Runnable = token?.registerCancelAction(connection::disconnect) ?: Runnable { }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "Jarvys/1.2.0 (Android ${Build.VERSION.RELEASE}; ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"})")
            token?.throwIfCancelled()
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("models.dev returned HTTP $status")
            val raw = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(connection.inputStream)
            } else connection.inputStream
            val bytes = raw.use(::readBounded)
            token?.throwIfCancelled()
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            return root
        } finally {
            unregister.run()
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_CATALOG_BYTES) { "models.dev catalog is too large" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    internal fun parseModelsDev(root: JSONObject): List<ModelInfo> {
        val openAiModels = root.optJSONObject("openai")?.optJSONObject("models") ?: return emptyList()
        val result = ArrayList<ModelInfo>()
        val keys = openAiModels.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = openAiModels.optJSONObject(key) ?: continue
            val id = item.optString("id", key)
            val reasoningMode = item.optJSONObject("options")?.optString("reasoningMode")
            if (!shouldInclude(id, reasoningMode)) continue
            result.add(toModelInfo(id, item))
        }
        return result
    }

    internal fun parseOpenRouterModelsDev(root: JSONObject): List<OpenRouterModelInfo> {
        val openRouterModels = root.optJSONObject("openrouter")?.optJSONObject("models") ?: return emptyList()
        val result = ArrayList<OpenRouterModelInfo>()
        val keys = openRouterModels.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = openRouterModels.optJSONObject(key) ?: continue
            val id = item.optString("id", key).trim()
            if (id.isEmpty()) continue
            val limit = item.optJSONObject("limit") ?: JSONObject()
            result.add(OpenRouterModelInfo(id, item.optString("name", id),
                limit.optInt("context", 0), limit.optInt("output", 0),
                readStringSet(item.optJSONObject("modalities")?.optJSONArray("input"))))
        }
        return result.sortedBy { it.name.lowercase() }
    }

    internal fun parseOpenAiApiModelsDev(root: JSONObject): List<ModelInfo> {
        val models = root.optJSONObject("openai")?.optJSONObject("models") ?: return emptyList()
        val result = ArrayList<ModelInfo>()
        val keys = models.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = models.optJSONObject(key) ?: continue
            if (!item.optBoolean("tool_call", false)) continue
            val modalities = item.optJSONObject("modalities") ?: continue
            val input = readStringSet(modalities.optJSONArray("input"))
            val output = readStringSet(modalities.optJSONArray("output"))
            if ("text" !in input || "text" !in output) continue
            val id = item.optString("id", key)
            val limit = item.optJSONObject("limit") ?: JSONObject()
            result.add(ModelInfo(id, item.optString("name", id), limit.optInt("context", 0),
                limit.optInt("output", 0), input, output, emptyList()))
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun toModelInfo(id: String, item: JSONObject): ModelInfo {
        val limit = item.optJSONObject("limit") ?: JSONObject()
        val modalities = item.optJSONObject("modalities") ?: JSONObject()
        val variants = ArrayList<ModelVariant>()
        val reasoningOptions = item.optJSONArray("reasoning_options") ?: JSONArray()
        for (index in 0 until reasoningOptions.length()) {
            val option = reasoningOptions.optJSONObject(index) ?: continue
            if (option.optString("type") != "effort") continue
            val values = option.optJSONArray("values") ?: continue
            for (valueIndex in 0 until values.length()) {
                val effort = values.optString(valueIndex).trim()
                if (effort.isEmpty() || variants.any { it.id == effort }) continue
                val detailed = effort in setOf("high", "xhigh", "max")
                variants.add(ModelVariant(effort, effort, if (detailed) "detailed" else "auto", "medium"))
            }
        }
        return ModelInfo(
            id = id,
            name = item.optString("name", id),
            contextLimit = limit.optInt("context", 0),
            outputLimit = limit.optInt("output", 0),
            inputModalities = readStringSet(modalities.optJSONArray("input")),
            outputModalities = readStringSet(modalities.optJSONArray("output")),
            variants = variants,
        )
    }

    private fun serializeCatalog(models: List<ModelInfo>): JSONArray = JSONArray().apply {
        models.forEach { model ->
            put(JSONObject().apply {
                put("id", model.id)
                put("name", model.name)
                put("context", model.contextLimit)
                put("output", model.outputLimit)
                put("input", JSONArray(model.inputModalities.toList()))
                put("modalities_output", JSONArray(model.outputModalities.toList()))
                val serializedVariants = JSONArray()
                model.variants.forEach { variant ->
                    serializedVariants.put(
                        JSONObject()
                            .put("id", variant.id)
                            .put("effort", variant.reasoningEffort)
                            .put("summary", variant.reasoningSummary)
                            .put("verbosity", variant.textVerbosity),
                    )
                }
                put("variants", serializedVariants)
            })
        }
    }

    private fun parseStoredCatalog(json: String): List<ModelInfo> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val variants = item.getJSONArray("variants")
                add(ModelInfo(
                    id = item.getString("id"),
                    name = item.optString("name", item.getString("id")),
                    contextLimit = item.optInt("context"),
                    outputLimit = item.optInt("output"),
                    inputModalities = readStringSet(item.optJSONArray("input")),
                    outputModalities = readStringSet(item.optJSONArray("modalities_output")),
                    variants = buildList {
                        for (variantIndex in 0 until variants.length()) {
                            val variant = variants.getJSONObject(variantIndex)
                            add(ModelVariant(variant.getString("id"), variant.getString("effort"),
                                variant.optString("summary", "auto"), variant.optString("verbosity", "medium")))
                        }
                    },
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun serializeOpenRouterCatalog(models: List<OpenRouterModelInfo>): JSONArray = JSONArray().apply {
        models.forEach { model -> put(JSONObject().put("id", model.id).put("name", model.name)
            .put("context", model.contextLimit).put("output", model.outputLimit)
            .put("input", JSONArray(model.inputModalities.toList()))) }
    }

    private fun parseStoredOpenRouterCatalog(json: String): List<OpenRouterModelInfo> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(OpenRouterModelInfo(item.getString("id"), item.optString("name", item.getString("id")),
                    item.optInt("context"), item.optInt("output"), readStringSet(item.optJSONArray("input"))))
            }
        }
    }.getOrDefault(emptyList())

    private fun readStringSet(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun fallbackModels(): List<ModelInfo> {
        fun variants(vararg efforts: String) = efforts.map { effort ->
            val detailed = effort in setOf("high", "xhigh", "max")
            ModelVariant(effort, effort, if (detailed) "detailed" else "auto", "medium")
        }
        fun model(id: String, name: String, context: Int, output: Int, inputs: Set<String>, vararg efforts: String) =
            ModelInfo(id, name, context, output, inputs, setOf("text"), variants(*efforts))
        val common = setOf("text", "image", "pdf")
        val five = arrayOf("none", "low", "medium", "high", "xhigh")
        val six = arrayOf("low", "medium", "high", "xhigh", "max")
        return listOf(
            model("gpt-5.4", "GPT-5.4", 1_050_000, 128_000, common, *five),
            model("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", 128_000, 32_000, common, *five),
            model("gpt-6-astra", "GPT-6 Astra", 1_050_000, 128_000, common, *six),
            model("gpt-5.4-mini", "GPT-5.4 mini", 400_000, 128_000, setOf("text", "image"), *five),
            model("gpt-5.6-luna", "GPT-5.6 Luna", 1_050_000, 128_000, common, *five, "max"),
            model("gpt-5.5", "GPT-5.5", 1_050_000, 128_000, common, *five),
            model("gpt-6-luna", "GPT-6 Luna", 1_050_000, 128_000, common, *six),
            model("gpt-5.6-terra", "GPT-5.6 Terra", 1_050_000, 128_000, common, *five, "max"),
            model("gpt-5.6-sol", "GPT-5.6 Sol", 1_050_000, 128_000, common, *five, "max"),
            model("gpt-6-sol", "GPT-6 Sol", 1_050_000, 128_000, common, *six),
        )
    }
}
