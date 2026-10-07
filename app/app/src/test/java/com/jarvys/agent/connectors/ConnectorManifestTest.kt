package com.jarvys.agent.connectors

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.jarvys.agent.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class ConnectorManifestTest {
    @Test fun registeredDeviceConnectorGuidanceContainsNoQuotedUserReplySamples() {
        // This intentionally checks phrase cues, not all quotes: examples of argument/data formats remain valid.
        val responseExample = Regex("(?i)\\b(?:such as|e\\.g\\.|for example)\\s+[‘'“\"]")
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> args?.get(1) ?: false
                "getString" -> args?.get(1)
                else -> null
            }
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }

        val registeredDefinitions = ConnectorRegistry.deviceDefinitions(context)
        assertTrue("No device connector definitions were registered for ${BuildConfig.FLAVOR}", registeredDefinitions.isNotEmpty())
        assertTrue(registeredDefinitions.any { it.id == LocationConnector.ID })
        registeredDefinitions.forEach { definition ->
            val modelGuidance = buildList {
                add("connector description" to definition.description)
                add("usage note" to definition.usageNote())
                definition.operations.forEach { add("operation ${it.name} description" to it.description) }
            }
            modelGuidance.forEach { (location, text) ->
                assertFalse(
                    "${definition.name} $location contains a quoted response example: $text",
                    responseExample.containsMatchIn(text),
                )
            }
        }
    }

    @Test fun eachFlavorHasExactlyItsAllowedSensitivePermissionsAndTheListenerService() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val mainManifest = sequenceOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "src/main/AndroidManifest.xml"),
            File(root.parentFile, "app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: error("Could not locate the main AndroidManifest.xml from ${root.path}")
        val sourceRoot = requireNotNull(mainManifest.parentFile).parentFile
        val flavorManifest = File(sourceRoot, "${BuildConfig.FLAVOR}/AndroidManifest.xml")
        val mainText = mainManifest.readText()
        val flavorText = if (flavorManifest.isFile) flavorManifest.readText() else ""
        val declared = usesPermissions(mainText) + usesPermissions(flavorText)
        val base = setOf(
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_CONTACTS",
            "android.permission.CALL_PHONE",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )
        val fullOnly = setOf(
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_CALL_LOG",
        )
        val expected = base + if (BuildConfig.FLAVOR == "full") fullOnly else emptySet()
        assertEquals("Unexpected uses-permission for ${BuildConfig.FLAVOR}", expected, declared)

        val forbidden = setOf(
            "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH", "android.permission.WRITE_SMS",
            "android.permission.WRITE_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.ANSWER_PHONE_CALLS", "android.permission.WRITE_CONTACTS",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
        )
        assertTrue("Forbidden permission found: ${declared intersect forbidden}", declared.intersect(forbidden).isEmpty())

        val listener = Regex(
            "(?s)<service(?=[^>]*android:name=\"com\\.jarvys\\.agent\\.connectors\\.JarvysNotificationListenerService\")[^>]*>(.*?)</service>",
        ).find(mainText)?.value ?: error("Notification listener service declaration is missing")
        assertTrue(listener.contains("android:permission=\"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE\""))
        assertTrue(listener.contains("android:exported=\"false\""))
        assertTrue(listener.contains("android:name=\"android.service.notification.NotificationListenerService\""))

        val fullSms = File(sourceRoot, "full/java/com/jarvys/agent/connectors/FullSmsConnector.kt")
        val callLog = File(sourceRoot, "full/java/com/jarvys/agent/connectors/CallLogConnector.kt")
        assertTrue(fullSms.isFile)
        assertTrue(callLog.isFile)
        if (BuildConfig.FLAVOR == "play") {
            assertFalse(classIsPresent("com.jarvys.agent.connectors.FullSmsConnector"))
            assertFalse(classIsPresent("com.jarvys.agent.connectors.CallLogConnector"))
        } else {
            assertTrue(classIsPresent("com.jarvys.agent.connectors.FullSmsConnector"))
            assertTrue(classIsPresent("com.jarvys.agent.connectors.CallLogConnector"))
        }
    }

    @Test fun googleRestAndWorkspaceScopesExistOnlyInFullSourceSet() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val mainManifest = sequenceOf(
            File(root, "app/src/main/AndroidManifest.xml"), File(root, "src/main/AndroidManifest.xml"),
            File(root.parentFile, "app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: error("Could not locate the main AndroidManifest.xml from ${root.path}")
        val sourceRoot = requireNotNull(mainManifest.parentFile).parentFile
        val flavorText = File(sourceRoot, "${BuildConfig.FLAVOR}/AndroidManifest.xml")
            .takeIf { it.isFile }?.readText().orEmpty()
        val forbidden = Regex("(?i)(GoogleOAuthManager|GmailConnector|DriveConnector|gmail\\.(?:readonly|compose|send|modify)|drive\\.(?:readonly|file)|gmail\\.googleapis\\.com|googleapis\\.com/gmail/v1|googleapis\\.com/drive/v3|accounts\\.google\\.com/o/oauth2|oauth2\\.googleapis\\.com)")
        listOf("main", "play").forEach { sourceSet ->
            val dir = File(sourceRoot, sourceSet)
            val matches = dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
                .flatMap { file -> forbidden.findAll(file.readText()).map { "${file.path}: ${it.value}" } }.toList()
            assertTrue("Google Workspace REST identifiers/scopes leaked into src/$sourceSet: $matches", matches.isEmpty())
        }
        assertTrue(classIsPresent("com.jarvys.agent.connectors.GoogleOAuthManager") == (BuildConfig.FLAVOR == "full"))
        assertTrue(classIsPresent("com.google.android.gms.auth.api.identity.AuthorizationClient") == (BuildConfig.FLAVOR == "full"))
        assertFalse("Google's raw custom-scheme handler is not supported by its Android OAuth client",
            flavorText.contains("GoogleOAuthRedirectActivity") || flavorText.contains("googleOAuthRedirectScheme"))
    }

    private fun usesPermissions(text: String): Set<String> = Regex("<uses-permission\\s+android:name=\"([^\"]+)\"\\s*/>")
        .findAll(text).map { it.groupValues[1] }.toSet()

    private fun classIsPresent(name: String): Boolean = runCatching { Class.forName(name) }.isSuccess
}
