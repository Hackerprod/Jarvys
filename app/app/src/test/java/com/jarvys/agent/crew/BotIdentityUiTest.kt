package com.jarvys.agent.crew

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.jarvysColorScheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class BotIdentityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository get() = CrewProfileRepository(compose.activity)
    private fun create(id: String = "custom-picture", name: String = "Same name") = repository.create(
        CrewProfile(id, 1, name, "Synthetic icon fixture", "Use only declared tools.", emptyList(), emptyList()), emptyList(), emptyList())
    private fun icon(bot: BotDefinition, ref: String, color: Int): BotDefinition {
        val file = File(compose.activity.filesDir, "bot_icons/${bot.id}/$ref")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pixels = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        pixels.eraseColor(color)
        for (y in 0..15) for (x in 0..15) pixels.setPixel(x, y, Color.WHITE)
        file.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        pixels.recycle()
        return repository.setIcon(bot.id, bot.revision, ref)
    }
    private val ref1 = "00000000-0000-0000-0000-000000000001.png"
    private val ref2 = "00000000-0000-0000-0000-000000000002.png"
    private fun source(tag: String): String {
        fun find(node: androidx.compose.ui.semantics.SemanticsNode): String? =
            if (node.config.contains(BotIconSourceKey)) node.config[BotIconSourceKey] else node.children.firstNotNullOfOrNull(::find)
        return requireNotNull(find(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()))
    }
    private fun expectSource(tag: String, expected: String) {
        compose.waitUntil(10_000) { runCatching { source(tag) == expected }.getOrDefault(false) }
        assertEquals(expected, source(tag))
    }
    private fun snapshot(role: String, status: String = "DONE") = CrewMissionSnapshot("identity-mission", "identity-chat", "process",
        "Synthetic identity check", "RUNNING", "", 100, 500,
        listOf(CrewBotSnapshot("instance-different-from-profile", role, "Original role", "Same name", "unrelated-color",
            "Read the fixture", status, "", "", "", emptyList(), 100, 500)),
        listOf(CrewMessage("identity-message", "identity-chat", "instance-different-from-profile", "chief", CrewMessage.Type.FINDING,
            "Synthetic result", emptyList(), 200)))

    @Test fun codingCatalogAndMissionPixelsMatchAtEveryAvatarSize() = pixelsMatch(BotDefinition(CrewProfile.codingDefault(), 1, true, true, ""), "builtin:terminal")
    @Test fun androidCatalogAndMissionPixelsMatchAtEveryAvatarSize() = pixelsMatch(BotDefinition(CrewProfile.androidDefault(), 1, true, true, ""), "builtin:smartphone")
    @Test fun generatedCatalogAndMissionPixelsMatchAtEveryAvatarSize() {
        val bot = icon(create(), ref1, Color.BLUE)
        pixelsMatch(bot, "generated:${bot.id}/$ref1")
    }
    private fun pixelsMatch(bot: BotDefinition, expected: String) {
        var edge by mutableStateOf(72)
        var mission by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            // Compare at the same origin: translated native rounded-edge antialiasing can differ by one alpha unit.
            Box(Modifier.fillMaxSize()) {
                if (mission) CrewBotAvatar("Unrelated instance name", bot.id, "unrelated-color", "FAILED",
                    modifier = Modifier.size(edge.dp), testTag = "identity")
                else BotCatalogIcon(bot, Modifier.size(edge.dp).testTag("identity"))
            }
        } } }
        for (size in listOf(72, 42, 28)) {
            compose.runOnIdle { edge = size; mission = false }
            expectSource("identity", expected); awaitReactionDrawIdle(compose)
            val a = crop("identity")
            compose.runOnIdle { mission = true }
            expectSource("identity", expected); awaitReactionDrawIdle(compose)
            val b = crop("identity")
            val output = TestCaptureDirectories.named("bots-ux24-icons")
            File(output, "${bot.id}-$size.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "${bot.id}-$size-mission.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("UX24_ICON_CAPTURE=${File(output, "${bot.id}-$size.png").absolutePath}")
            println("UX24_ICON_CAPTURE=${File(output, "${bot.id}-$size-mission.png").absolutePath}")
            assertTrue("The exact shared identity must render equally at $size dp", a.sameAs(b))
            a.recycle(); b.recycle()
        }
    }

    private fun crop(tag: String): Bitmap {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val entire = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(entire))
            bitmap = Bitmap.createBitmap(entire, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
            entire.recycle()
        }
        return bitmap
    }

    private fun capture(name: String) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = TestCaptureDirectories.named("bots-ux24-missions")
            val file = File(output, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            println("UX24_CAPTURE=${file.absolutePath}")
        }
    }

    @Test fun liveIconUpdateAndJsonReopenUseCurrentStableProfileWithoutRuntimeMutation() {
        var bot by mutableStateOf(icon(create(), ref1, Color.BLUE))
        var mission by mutableStateOf(snapshot(bot.id))
        var open by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { BotCatalogIcon(bot, Modifier.size(72.dp).testTag("catalog")); if (open) CrewMissionCard(mission, {}, true) }
        } } }
        val tag = "crew-orb-instance-different-from-profile"
        expectSource(tag, "generated:${bot.id}/$ref1")
        capture("mission-original")
        val originalVersion = bot.profile.version
        val restored = CrewMissionSnapshot.fromJson(mission.toJson())
        compose.runOnIdle { bot = icon(bot, ref2, Color.RED) }
        expectSource("catalog", "generated:${bot.id}/$ref2"); expectSource(tag, "generated:${bot.id}/$ref2")
        assertEquals(originalVersion, repository.definition(bot.id).profile.version)
        assertEquals("unrelated-color", mission.bots.single().colorKey)
        assertEquals("instance-different-from-profile", restored.bots.single().id)
        compose.runOnIdle { open = false }
        compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.runOnIdle { mission = restored; open = true }
        expectSource(tag, "generated:${bot.id}/$ref2")
        assertEquals(ref2, CrewProfileRepository(compose.activity).definition(bot.id).iconRef)
        capture("mission-updated-reopened")
    }

    @Test fun sameDisplayNameDoesNotMixDistinctProfileImages() {
        val first = icon(create("custom-first"), ref1, Color.BLUE)
        val second = icon(create("custom-second"), ref1, Color.RED)
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            Row { CrewBotAvatar("Same name", first.id, "same-color", "DONE", testTag = "first")
                CrewBotAvatar("Same name", second.id, "same-color", "DONE", testTag = "second") }
        } } }
        expectSource("first", "generated:${first.id}/$ref1"); expectSource("second", "generated:${second.id}/$ref1")
        awaitReactionDrawIdle(compose)
        assertFalse(crop("first").sameAs(crop("second")))
    }

    @Test fun missingAndCorruptGeneratedImagesShareCatalogFallback() {
        var bot by mutableStateOf(repository.setIcon(create().id, 1, ref1))
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            Row { BotCatalogIcon(bot, Modifier.size(42.dp).testTag("catalog"))
                CrewBotAvatar("Old name", bot.id, "color", "DONE", testTag = "mission") }
        } } }
        expectSource("catalog", "builtin:bot"); expectSource("mission", "builtin:bot")
        val invalid = File(compose.activity.filesDir, "bot_icons/${bot.id}/$ref2")
        check(invalid.parentFile!!.isDirectory || invalid.parentFile!!.mkdirs()); invalid.writeText("Not an image")
        compose.runOnIdle { bot = repository.setIcon(bot.id, bot.revision, ref2) }
        expectSource("catalog", "builtin:bot"); expectSource("mission", "builtin:bot")
    }

    @Test fun identitySwitchNeverRetainsAnotherBotsDecodedImage() {
        var identity by mutableStateOf(icon(create(), ref1, Color.BLUE).let { BotIconIdentity(it.id, it.iconRef) })
        compose.setContent { MaterialTheme(colorScheme = jarvysColorScheme(true)) { BotIdentityIcon(identity, Modifier.size(72.dp).testTag("identity")) } }
        expectSource("identity", "generated:custom-picture/$ref1")
        compose.runOnIdle { identity = BotIconIdentity("custom-missing", ref1) }
        assertNotEquals("generated:custom-picture/$ref1", source("identity"))
        expectSource("identity", "builtin:bot")
    }

    @Test fun builtinIdentityCannotBeReplacedByForgedMetadata() {
        val forged = BotDefinition(CrewProfile.codingDefault(), 1, true, false, ref1)
        compose.setContent { MaterialTheme(colorScheme = jarvysColorScheme(true)) { BotCatalogIcon(forged, Modifier.size(42.dp).testTag("identity")) } }
        expectSource("identity", "builtin:terminal")
    }

    @Test fun missingCustomProfileKeepsStableGenericFallbackWithoutGuessingByName() {
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            CrewBotAvatar("Coding", "custom-no-longer-available", "coding", "DONE", testTag = "identity")
        } } }
        expectSource("identity", "builtin:bot")
    }

    @Test fun profileBackedApprovalUsesTheSameGeneratedImage() {
        val bot = icon(create(), ref1, Color.BLUE)
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            CrewApprovalAttribution("Previous name", bot.id)
        } } }
        expectSource("crew-approval-requester-orb", "generated:${bot.id}/$ref1")
    }

    @Test fun detailAndDebateUseGeneratedIdentityAndReceiveLiveUpdates() {
        var bot = icon(create(), ref1, Color.BLUE)
        val mission = snapshot(bot.id)
        val constructor = com.jarvys.agent.WorkspaceStore::class.java.getDeclaredConstructor(File::class.java, String::class.java)
        constructor.isAccessible = true
        val board = CrewBoard(constructor.newInstance(TestCaptureDirectories.create("bot-identity-board"), com.jarvys.agent.WorkspaceStore.projectIdForSession("identity-chat")))
        var detail by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true) { MaterialTheme(colorScheme = jarvysColorScheme(true)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (detail) CrewBotDetailScreen(mission, mission.bots.single().id, board, true, { _, _ -> }, { _, _ -> }, {}, {})
            else CrewMissionScreen(mission, board, true, {}, { _, _ -> }, {})
            }
        } } }
        expectSource("crew-message-orb-identity-message", "generated:${bot.id}/$ref1")
        compose.onNodeWithTag("crew-tab-bots").performClick()
        expectSource("crew-bot-row-instance-different-from-profile", "generated:${bot.id}/$ref1")
        compose.runOnIdle { detail = true }
        expectSource("crew-bot-detail-orb", "generated:${bot.id}/$ref1")
        expectSource("crew-message-orb-identity-message", "generated:${bot.id}/$ref1")
        compose.runOnIdle { bot = icon(bot, ref2, Color.RED) }
        expectSource("crew-bot-detail-orb", "generated:${bot.id}/$ref2")
        expectSource("crew-message-orb-identity-message", "generated:${bot.id}/$ref2")
        capture("detail-and-debate-updated")
    }

    @Test fun catalogMissionMotionRunsOnlyWhileActuallyRunning() {
        var status by mutableStateOf("IDLE")
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = jarvysColorScheme(true)) { CrewBotAvatar("Coding", "coding", "color", status, testTag = "identity") } }
        for (state in listOf("IDLE", "QUEUED", "WAITING", "DONE", "FAILED", "INTERRUPTED", "RUNNING", "DONE")) {
            compose.runOnIdle { status = state }
            compose.mainClock.advanceTimeBy(80)
            val motion = compose.onNodeWithTag("bot-working-motion-coding", useUnmergedTree = true)
            if (state == "RUNNING") motion.assertExists() else motion.assertDoesNotExist()
            assertEquals("builtin:terminal", source("identity"))
        }
    }
}
