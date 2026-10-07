package com.jarvys.agent.tasks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagementLocalizationTest {
    @Test fun newTaskManagementStringsHaveEnglishSpanishParity() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val root = sequenceOf(working, File(working, "app"), File(working.parentFile, "app"))
            .first { File(it, "src/main/res/values/strings.xml").isFile }
        fun names(path: File) = Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"")
            .findAll(path.readText()).map { it.groupValues[1] }.toSet()
        val english = names(File(root, "src/main/res/values/strings.xml"))
        val spanish = names(File(root, "src/main/res/values-es/strings.xml"))
        assertTrue(english.any { it.startsWith("task_management_") })
        assertTrue(english.any { it.startsWith("tasks_") })
        assertEquals(english, spanish)
    }
}
