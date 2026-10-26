package com.jarvys.agent.connectors

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R

interface PhoneCallGateway {
    fun placeCall(phoneNumber: String)
    fun openDialer(phoneNumber: String)
}
class AndroidPhoneCallGateway(context: Context) : PhoneCallGateway {
    private val appContext = context.applicationContext

    override fun placeCall(phoneNumber: String) {
        val telecom = requireNotNull(appContext.getSystemService(TelecomManager::class.java)) {
            "Android Telecom service is unavailable"
        }
        telecom.placeCall(Uri.fromParts("tel", phoneNumber, null), Bundle.EMPTY)
    }

    override fun openDialer(phoneNumber: String) {
        appContext.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class DialerConnector(
    private val gateway: PhoneCallGateway,
    private val callPermissionGranted: () -> Boolean,
    private val emergencyNumber: (String) -> Boolean,
) : ConnectorRuntime {
    override fun hasCompleteWriteAdmissionCoverage() = true
    override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
    override fun disconnect() = Unit

    override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
        error("Phone actions must pass through the connector action-policy gate")

    override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation {
        require(operation == OPEN_DIALER || operation == PLACE_CALL) { "Unknown phone operation: $operation" }
        token.throwIfCancelled()
        val number = normalizePhone(arguments.optString("phoneNumber"), emergencyNumber)
        val openDialer = ApprovalIntentSpec(ApprovalIntentKind.DIAL, dataUri = "tel:$number")
        return if (operation == OPEN_DIALER) {
            ConnectorWritePreparation(
                ApprovalSummary(
                    title = "Open Phone Dialer",
                    lines = listOf("Open the dialer for $number", "Jarvys will not place the call."),
                    activityIntent = openDialer,
                    localizedTitle = ConnectorUiText(R.string.approval_summary_phone_dialer_title, fallback = "Open Phone Dialer"),
                    localizedLines = listOf(
                        ConnectorUiText(R.string.approval_summary_phone_dialer_open, listOf(number), "Open the dialer for $number"),
                        ConnectorUiText(R.string.approval_summary_phone_dialer_no_call, fallback = "Jarvys will not place the call."),
                    ),
                ),
                JSONObject(arguments.toString()),
                attachment = number,
            )
        } else {
            val localizedTitle = ConnectorUiText(R.string.approval_summary_phone_call_title, fallback = "Place Phone Call")
            ConnectorWritePreparation(
                ApprovalSummary(
                    title = "Place Phone Call",
                    lines = listOf("Request a call to $number"),
                    permissionDeniedIntent = openDialer,
                    localizedTitle = localizedTitle,
                    localizedLines = listOf(ConnectorUiText(R.string.approval_summary_phone_call, listOf(number), "Request a call to $number")),
                    compactSummary = ConnectorUiText(R.string.approval_summary_compact,
                        listOf(localizedTitle, ConnectorUiText(fallback = number)), "Place Phone Call Â· $number"),
                ),
                JSONObject(arguments.toString()),
                attachment = number,
                fallbackOnPermissionDenied = true,
            )
        }
    }

    override fun invokePrepared(
        operation: String,
        arguments: JSONObject,
        preparation: ConnectorWritePreparation,
        token: CancellationToken,
    ): JSONObject {
        token.throwIfCancelled()
        val number = preparation.attachment as? String
            ?: normalizePhone(arguments.optString("phoneNumber"), emergencyNumber)
        if (operation == OPEN_DIALER || preparation.permissionDeniedFallback) {
            return dialerFallbackResult(number)
        }
        require(operation == PLACE_CALL) { "Unknown phone operation: $operation" }
        if (!callPermissionGranted()) return openDialerFallback(number, preparation)
        (preparation.authorizeWriteEffect ?: preparation.authorizeEffect)?.invoke()
        try {
            gateway.placeCall(number)
            preparation.reportWriteOutcome?.invoke(com.jarvys.agent.EffectInvocation.Outcome.OK)
            return JSONObject().put("source", "android.phone").put("untrusted_content", false)
                .put("status", "call_requested")
                .put("phoneNumber", number)
                .put("message", "Call request accepted by Android Telecom.")
        } catch (_: SecurityException) {
            return openDialerFallback(number, preparation).also {
                preparation.reportWriteOutcome?.invoke(com.jarvys.agent.EffectInvocation.Outcome.UNCERTAIN)
            }
        } catch (_: IllegalArgumentException) {
            return openDialerFallback(number, preparation).also {
                preparation.reportWriteOutcome?.invoke(com.jarvys.agent.EffectInvocation.Outcome.UNCERTAIN)
            }
        }
    }

    private fun openDialerFallback(number: String, preparation: ConnectorWritePreparation): JSONObject {
        (preparation.authorizeWriteEffect ?: preparation.authorizeEffect)?.invoke()
        try {
            gateway.openDialer(number)
        } catch (_: RuntimeException) {
            error("Telecom could not place the call and Android did not allow the dialer to open; no call was placed")
        }
        preparation.reportWriteOutcome?.invoke(com.jarvys.agent.EffectInvocation.Outcome.OK)
        return dialerFallbackResult(number)
    }

    private fun dialerFallbackResult(number: String) = JSONObject().put("source", "android.phone")
        .put("untrusted_content", false).put("status", "dialer_launch_requested")
        .put("phoneNumber", number)
        .put("callPlaced", false)
        .put("message", "Android accepted the dialer launch request; presentation is not confirmed. Jarvys did not initiate the call. If the dialer appears, press Call there to place it.")

    companion object {
        const val ID = "phone"
        const val OPEN_DIALER = "open_dialer"
        const val PLACE_CALL = "place_call"
        const val MAX_NUMBER_CHARS = 40
        private val EMERGENCY_NUMBERS = setOf("112", "911", "999", "000", "110", "118", "119", "08")

        @JvmStatic
        fun definition(context: Context): ConnectorDefinition {
            val appContext = context.applicationContext
            return definition(
                AndroidPhoneCallGateway(appContext),
                { ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED },
                { number -> isKnownEmergencyNumber(number) || PhoneNumberUtils.isEmergencyNumber(number) },
            )
        }

        internal fun definition(
            gateway: PhoneCallGateway,
            callPermissionGranted: () -> Boolean,
            emergencyNumber: (String) -> Boolean = { it.removePrefix("+") in EMERGENCY_NUMBERS },
        ) = ConnectorDefinition(
            id = ID,
            name = "Phone",
            version = "1",
            description = "Place a direct call through Android Telecom. A dialer fallback requires the user to place the call. Emergency numbers and USSD are unsupported.",
            capabilities = listOf("phone.dial_intent", "phone.place_call"),
            operations = listOf(
                ConnectorOperation(
                    name = OPEN_DIALER,
                    displayLabel = "Open Phone Dialer",
                    description = "Request ACTION_DIAL according to the configured action policy. The user presses Call in the system dialer.",
                    inputSchema = dialerSchema(),
                    write = true,
                    limits = mapOf("phoneNumberChars" to MAX_NUMBER_CHARS),
                    autonomyAllowed = true,
                    permissionsOverride = true,
                    displayLabelResourceId = R.string.connector_operation_phone_dialer,
                    descriptionResourceId = R.string.connector_operation_phone_dialer_description,
                ),
                ConnectorOperation(
                    name = PLACE_CALL,
                    displayLabel = "Place Phone Call",
                    description = "Request a call through TelecomManager.placeCall with CALL_PHONE according to the configured action policy. If the call request fails, open ACTION_DIAL and require the user to press Call. Emergency and USSD numbers are rejected.",
                    inputSchema = dialerSchema(),
                    write = true,
                    requiredPermissions = listOf(Manifest.permission.CALL_PHONE),
                    limits = mapOf("phoneNumberChars" to MAX_NUMBER_CHARS),
                    displayLabelResourceId = R.string.connector_operation_phone_call,
                    descriptionResourceId = R.string.connector_operation_phone_call_description,
                ),
            ),
            runtime = DialerConnector(gateway, callPermissionGranted, emergencyNumber),
            writePermissions = listOf(Manifest.permission.CALL_PHONE),
            permissionLabelResourceId = R.string.connector_permission_phone,
            displayNameResourceId = R.string.connector_label_phone,
            descriptionResourceId = R.string.connector_description_phone,
            usageNoteProvider = { "For a successful place_call result with status=call_requested, respond in one short sentence in the user's language identifying the requested destination. Never claim the call connected or was answered. Do not add a connection-status caveat unless the user asks whether it connected or the result indicates fallback or failure. If ACTION_DIAL opens, explicitly tell the user they must press Call in that dialer. Emergency numbers and USSD numbers are unsupported." },
        )

        internal fun dialerSchema() = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("phoneNumber", JSONObject().put("type", "string")
                .put("minLength", 1).put("maxLength", MAX_NUMBER_CHARS)))
            .put("required", org.json.JSONArray(listOf("phoneNumber")))
            .put("additionalProperties", false)

        private fun isKnownEmergencyNumber(number: String): Boolean = number.removePrefix("+") in EMERGENCY_NUMBERS

        internal fun normalizePhone(raw: String, emergencyNumber: (String) -> Boolean = ::isKnownEmergencyNumber): String {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= MAX_NUMBER_CHARS) { "Enter a phone number of at most $MAX_NUMBER_CHARS characters" }
            require(trimmed.all { it.isDigit() || it in "+ -" }) { "Phone number may contain only digits, +, spaces, and hyphens; USSD is unsupported" }
            val compact = trimmed.filter { it.isDigit() || it == '+' }
            require(compact.drop(1).none { it == '+' }) {
                "Phone number may contain digits and one leading + only"
            }
            require(compact.count(Char::isDigit) in 3..15) { "Phone number must contain 3-15 digits" }
            require(!emergencyNumber(compact)) { "Emergency numbers cannot be called by Jarvys; use the Phone app directly" }
            return compact
        }
    }
}
