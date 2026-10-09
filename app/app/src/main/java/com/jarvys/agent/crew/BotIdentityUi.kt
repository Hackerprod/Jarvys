package com.jarvys.agent.crew

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import com.jarvys.agent.BotIconStore
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.ui.motion.rememberMotionEnabled
import com.jarvys.agent.ui.motion.rememberMotionViewport
import com.jarvys.agent.ui.shell.drawEffortLightRay
import com.jarvys.agent.ui.shell.effortSparkleAlpha
import com.jarvys.agent.ui.shell.rememberEffortSparklePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presentation identity only. Never changes the mission's pinned executable configuration. */
internal data class BotIconIdentity(val id: String, val iconRef: String = "")
private data class LoadedBotIcon(val bitmap: ImageBitmap?, val complete: Boolean)
internal const val BOT_WORKING_SWEEP_ALPHA = 0.24f
internal val BotIconSourceKey = SemanticsPropertyKey<String>("BotIconSource")

/** Read the current icon by stable role/profile ID, including while a historical mission is open. */
@Composable
internal fun rememberCatalogBotIcon(roleId: String?): BotIconIdentity? {
    if (roleId == "chief" || roleId == "captain") return remember { BotIconIdentity("chief") }
    if (roleId == null || (!CrewProfileRepository.isBuiltInId(roleId) && !roleId.startsWith("custom-"))) return null
    if (CrewProfileRepository.isBuiltInId(roleId)) return remember(roleId) { BotIconIdentity(roleId) }
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { runCatching { CrewProfileRepository(context) }.getOrNull() }
    val scope = rememberCoroutineScope()
    var definition by remember(context, roleId) { mutableStateOf<BotDefinition?>(null) }
    fun accept(updated: BotDefinition) {
        if (updated.id == roleId && updated.revision >= (definition?.revision ?: 0)) definition = updated
    }
    // Subscribe before loading. A newer callback must win over an older in-flight disk read.
    DisposableEffect(repository, roleId) {
        val remove = repository?.addChangeListener { updated ->
            if (updated.id == roleId) scope.launch { accept(updated) }
        }
        onDispose { remove?.run() }
    }
    LaunchedEffect(repository, roleId) {
        withContext(Dispatchers.IO) { runCatching { repository?.definition(roleId) }.getOrNull() }?.let(::accept)
    }
    return BotIconIdentity(roleId, definition?.iconRef.orEmpty())
}

/** One renderer and bounded private decoder are shared by catalog, cards, details and debate. */
@Composable
internal fun BotIdentityIcon(identity: BotIconIdentity, modifier: Modifier = Modifier) {
    if (identity.id == "chief" || identity.id == "captain") {
        val context = LocalContext.current
        val pixels = remember(context) {
            val drawable = requireNotNull(androidx.core.content.ContextCompat.getDrawable(context,com.jarvys.agent.R.mipmap.ic_launcher))
            val bitmap = android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,256,256); drawable.draw(android.graphics.Canvas(bitmap)); bitmap.asImageBitmap()
        }
        Image(pixels,contentDescription=null,
            modifier=modifier.clip(RoundedCornerShape(percent=30)).semantics { this[BotIconSourceKey]="principal:launcher" },
            contentScale=ContentScale.Fit)
        return
    }
    val context = LocalContext.current.applicationContext
    val iconRef = identity.iconRef.takeUnless { CrewProfileRepository.isBuiltInId(identity.id) }.orEmpty()
    // A new identity gets a fresh state immediately, never one frame of the previous bot's bitmap.
    val loaded = key(context, identity.id, iconRef) {
        val result by produceState(LoadedBotIcon(null, iconRef.isBlank())) {
            val bitmap = if (iconRef.isBlank()) null else withContext(Dispatchers.IO) {
                runCatching {
                    val file = BotIconStore(context).resolve(identity.id, iconRef)
                    if (file.length() !in 1..(4L * 1024 * 1024)) return@runCatching null
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096) return@runCatching null
                    options.inJustDecodeBounds = false
                    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 256).coerceAtLeast(1)
                    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
                }.getOrNull()
            }
            value = LoadedBotIcon(bitmap, true)
        }
        result
    }
    val bitmap = loaded.bitmap
    val fallback = when (identity.id) {
        "coding" -> "terminal"
        "android-use" -> "smartphone"
        else -> "bot"
    }
    Box(modifier.clip(RoundedCornerShape(percent = 30)).background(MaterialTheme.colorScheme.primaryContainer)
        .semantics { this[BotIconSourceKey] = when {
            !loaded.complete -> "loading:${identity.id}/$iconRef"
            bitmap == null -> "builtin:$fallback"
            else -> "generated:${identity.id}/$iconRef"
        } },
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (loaded.complete) Icon(when (identity.id) { "coding" -> LucideIcons.Terminal; "android-use" -> LucideIcons.Smartphone; else -> LucideIcons.Bot },
                contentDescription = null, Modifier.fillMaxSize(0.528f), tint = MaterialTheme.colorScheme.primary)
    }
}

/** A single phase sweeps the rendered icon AND name; no background rectangle is introduced. */
@Composable
internal fun BotWorkingVisual(working: Boolean, id: String, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit) {
    val viewport = rememberMotionViewport()
    val phase = rememberEffortSparklePhase(rememberMotionEnabled(working, viewport.visible))
    val sweep = if (phase == null) Modifier else Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val center = size.width * (phase.value * 1.8f - 0.4f)
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = BOT_WORKING_SWEEP_ALPHA), Color.Transparent),
                start = Offset(center - size.width * 0.28f, 0f), end = Offset(center + size.width * 0.28f, size.height * 0.15f)),
                blendMode = BlendMode.SrcAtop)
        }
    Box(modifier.then(viewport.modifier), contentAlignment = Alignment.Center) {
        Box(sweep, contentAlignment = Alignment.Center, content = content)
        if (phase != null) Canvas(Modifier.matchParentSize().testTag("bot-working-motion-$id")) {
            val positions = arrayOf(Offset(0.28f, 0.08f), Offset(0.77f, 0.30f), Offset(0.18f, 0.55f),
                Offset(0.86f, 0.73f), Offset(0.32f, 0.94f), Offset(0.65f, 0.58f))
            positions.forEachIndexed { particle, position ->
                drawEffortLightRay(Offset(size.width * position.x, size.height * position.y),
                    effortSparkleAlpha(phase.value, particle), particle)
            }
        }
    }
}
