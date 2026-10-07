package com.jarvys.agent.tasks

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlatformContractTest {
    @Test fun javaTimeUsageIsGuardedByCoreLibraryDesugaringForMinSdk24() {
        val root = moduleRoot()
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertEquals(24, Regex("minSdk = (\\d+)").find(gradle)?.groupValues?.get(1)?.toInt())
        assertTrue(gradle.contains("isCoreLibraryDesugaringEnabled = true"))
        assertTrue(gradle.contains("coreLibraryDesugaring(\"com.android.tools:desugar_jdk_libs:2.1.5\")"))
        val sources = File(root, "app/src/main/java/com/jarvys/agent/tasks").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.any { it.readText().contains("java.time") })
    }

    @Test fun taskReceiverManifestIsNonExportedAndOnlyAddsRearmActionsWithoutPermissions() {
        val root = moduleRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
        val document = factory.newDocumentBuilder().parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length).map { receivers.item(it) }
            .single { receiverNode ->
                receiverNode.attributes.getNamedItem("android:name")?.nodeValue ==
                    "com.jarvys.agent.tasks.TaskRearmReceiver"
            }
        assertEquals("false", receiver.attributes.getNamedItem("android:exported")?.nodeValue)
        val actions = document.getElementsByTagName("action")
        val taskActions = (0 until actions.length).mapNotNull {
            val node = actions.item(it)
            if (node.parentNode.parentNode === receiver) {
                node.attributes.getNamedItem("android:name")?.nodeValue
            } else null
        }.toSet()
        assertEquals(setOf("android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED"), taskActions)
        val actionReceiver = (0 until receivers.length).map { receivers.item(it) }
            .single { it.attributes.getNamedItem("android:name")?.nodeValue == "com.jarvys.agent.tasks.TaskActionReceiver" }
        assertEquals("false", actionReceiver.attributes.getNamedItem("android:exported")?.nodeValue)
        val permissions = document.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).mapNotNull {
            val permission = permissions.item(it)
            if (permission.attributes.getNamedItem("tools:node")?.nodeValue == "remove") null
            else permission.attributes.getNamedItem("android:name")?.nodeValue
        }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.POST_NOTIFICATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.CALL_PHONE",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"), names)
        assertFalse(names.any { it.contains("SCHEDULE_EXACT_ALARM") || it.contains("RECEIVE_BOOT_COMPLETED") })
        assertTrue(manifest.readText().contains("android:allowBackup=\"false\""))

        val receiverSource = File(root, "app/src/main/java/com/jarvys/agent/tasks/TaskRearmReceiver.kt").readText()
        val receiveBody = receiverSource.substringAfter("override fun onReceive").substringBefore("\n    }")
        assertEquals(1, Regex("TaskScheduler\\.rearm").findAll(receiveBody).count())
        assertFalse(receiveBody.contains("TaskTickEngine"))
        val fullManifest = File(root, "app/src/full/AndroidManifest.xml").readText()
        assertEquals(3, Regex("<uses-permission").findAll(fullManifest).count())
    }

    @Test fun scheduledTaskPromptAndNotificationResourcesHaveSpanishParity() {
        val root = moduleRoot()
        fun keys(path: File): Set<String> {
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val nodes = factory.newDocumentBuilder().parse(path).getElementsByTagName("string")
            return (0 until nodes.length).mapNotNull { nodes.item(it).attributes.getNamedItem("name")?.nodeValue }.toSet()
        }
        val base = File(root, "app/src/main/res")
        val english = keys(File(base, "values/strings.xml"))
            .filter { it.startsWith("scheduled_task") || it.startsWith("scheduled_tasks") }.toSet()
        val spanish = keys(File(base, "values-es/strings.xml"))
            .filter { it.startsWith("scheduled_task") || it.startsWith("scheduled_tasks") }.toSet()
        assertTrue(english.isNotEmpty())
        assertEquals(english, spanish)
        val resources = File(base, "values/strings.xml").readText()
        assertTrue(resources.contains("name=\"scheduled_task_system_prompt\""))
        assertTrue(resources.contains("task_respond"))
        assertFalse(resources.contains("Linux", ignoreCase = true))
    }

    private fun moduleRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(working, working.parentFile, File(working, "app"), File(working.parentFile, "app"))
            .first { File(it, "app/build.gradle.kts").isFile }
    }
}
