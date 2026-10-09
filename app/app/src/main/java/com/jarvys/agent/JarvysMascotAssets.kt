package com.jarvys.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Original application identity, never a mutable catalog bot or an externally supplied Rive file. */
internal object JarvysMascotAssets {
    const val SOURCE_SHA256 = "983546e91329e6ab22e1191fbfb0f3c240384c8eabbacc62a1d96951e39e236b"
    const val ASSET_SHA256 = "514c6cfb16008eb6ecfd13c136ab6032c2ebd615c11f2325491fa762fc73981a"
    private val lock = Any()
    @Volatile private var cached: ByteArray? = null

    /** Loading/compiling never initializes JNI. Callers independently gate native playback. */
    suspend fun load(context: Context): ByteArray = withContext(Dispatchers.IO) {
        ensureActive()
        val bytes = cached ?: synchronized(lock) {
            cached ?: validate(
                read(context, "source.json", BotMascotSceneCompiler.MAX_SOURCE_BYTES),
                read(context, "asset.riv", BotMascotSceneCompiler.MAX_OUTPUT_BYTES),
            ).also { cached = it }
        }
        ensureActive()
        bytes.clone()
    }

    internal fun validate(source: ByteArray, asset: ByteArray): ByteArray {
        require(source.size in 1..BotMascotSceneCompiler.MAX_SOURCE_BYTES &&
            asset.size in 8..BotMascotSceneCompiler.MAX_OUTPUT_BYTES) { "Invalid bundled mascot size" }
        require(hash(source) == SOURCE_SHA256 && hash(asset) == ASSET_SHA256) { "Bundled mascot integrity failed" }
        return BotMascotPlaybackVerifier.verify(source, asset)
    }

    private fun read(context: Context, name: String, maxBytes: Int): ByteArray =
        context.applicationContext.assets.open("bot_mascots/jarvys/$name").use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Bundled mascot exceeds its size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
