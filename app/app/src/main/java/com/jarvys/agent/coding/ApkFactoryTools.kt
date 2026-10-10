package com.jarvys.agent.coding

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.connectors.ApprovalGate
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

/** Created only for the built-in Coding worker; never inherited by generic delegation. */
object ApkFactoryTools {
    const val NAME = "apk_factory"
    @JvmStatic fun create(context: Context, scope: ProjectScope, owner: String, available: BooleanSupplier): CoreTool =
        Tool(FactoryProjectService(context, scope, owner, ApprovalGate.INSTANCE, available::getAsBoolean))

    internal class Tool(private val service: FactoryProjectService) : CoreTool {
        override fun canDelegate() = false
        override fun declaration(): ToolSpec {
            val properties = linkedMapOf<String, Any>(
                "action" to mapOf("type" to "string", "enum" to listOf("inspect", "build", "sign", "preview", "preview_status", "test")),
                "spec_path" to mapOf("type" to "string"), "input_path" to mapOf("type" to "string"),
                "output_path" to mapOf("type" to "string"), "expected_sha256" to mapOf("type" to "string"),
                "expected_scope_version" to mapOf("type" to "integer"))
            return ToolSpec(NAME, "coding_apk_factory",
                "Inspect the bundled APK factory, build an unsigned separate Android app from factory.json/HTML/CSS/JS/icon in this project, or explicitly approve signing its exact verified artifact with a per-app device key. Read com.jarvys.apk-factory first. Preview opens one isolated shared-runtime harness with RAM app storage and a separate disposable browser profile for an exact verified build; preview_status reports bounded observed events. Test exercises shared native handlers, not website JavaScript or installed APK behavior. No per-app Gradle, network capability, installation, universal key or arbitrary native code. Preview policies do not certify zero traffic. Outputs never overwrite existing files. Signing always requires approval and key loss may prevent future updates.",
                "project", ToolSpec.Status.IMPLEMENTED, emptyMap(), listOf("action"),
                mapOf("type" to "object", "properties" to properties, "required" to listOf("action"), "additionalProperties" to false))
        }
        override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = try {
            token.throwIfCancelled()
            val action = string(arguments, "action")
            val expected = when (action) {
                "inspect" -> setOf("action")
                "build" -> setOf("action", "spec_path", "output_path", "expected_scope_version")
                "preview", "preview_status", "test" -> setOf("action", "input_path", "expected_sha256", "expected_scope_version")
                "sign" -> setOf("action", "input_path", "expected_sha256", "output_path", "expected_scope_version")
                else -> error("Unsupported factory action")
            }
            require(arguments.keys == expected) { "Use exactly these fields for $action: ${expected.joinToString()}" }
            val result: JSONObject = when (action) {
                "inspect" -> service.inspect(token)
                "build" -> service.build(string(arguments,"spec_path"), string(arguments,"output_path"), version(arguments), token)
                "preview", "preview_status", "test" -> service.harness(action, string(arguments,"input_path"), string(arguments,"expected_sha256"), version(arguments), token)
                else -> service.sign(string(arguments,"input_path"), string(arguments,"expected_sha256"), string(arguments,"output_path"), version(arguments), token)
            }
            CoreToolResult.success(result.toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { CoreToolResult.failure(failure.message ?: "Factory operation failed") }
        private fun string(args: Map<String, Any>, key: String) = args[key] as? String ?: error("$key must be a string")
        private fun version(args: Map<String, Any>): Long {
            val value = args["expected_scope_version"]
            require(value is Int || value is Long) { "expected_scope_version must be an integer" }
            return (value as Number).toLong().also { require(it >= 0) { "Invalid project version" } }
        }
    }
}
