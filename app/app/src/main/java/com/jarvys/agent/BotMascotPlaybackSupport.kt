package com.jarvys.agent

import android.content.Context
import app.rive.runtime.kotlin.core.RendererType
import app.rive.runtime.kotlin.core.Rive
import com.jarvys.agent.crew.BotMascotDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Explicit opt-in playback support. Loading a package never initializes the native runtime. */
internal object BotMascotPlaybackSupport {
    private val initialization = BotMascotNativeInitGate()
    val failureCode: String? get() = initialization.failureCode

    /** Call only after explicit temporary consent, while the playback surface is resumed. */
    fun initialize(context: Context): Boolean = initialization.initialize {
        Rive.init(context.applicationContext, RendererType.Rive)
    }

    /** No imported/arbitrary binary reaches JNI: regenerate our bounded contract before playback. */
    suspend fun load(context: Context, botId: String, descriptor: BotMascotDescriptor): ByteArray =
        withContext(Dispatchers.IO) {
            ensureActive()
            val stored = BotMascotStore(context).read(botId, descriptor)
            val verified = BotMascotPlaybackVerifier.verify(stored.source(), stored.riv())
            ensureActive()
            verified
        }
}

/** Process-scoped; failed initialization is cached too. Never retry on recomposition. */
internal class BotMascotNativeInitGate {
    private enum class State { UNTRIED, READY, FAILED }
    private var state = State.UNTRIED
    @get:Synchronized var failureCode: String? = null
        private set

    @Synchronized fun initialize(action: () -> Unit): Boolean {
        if (state != State.UNTRIED) return state == State.READY
        // Also blocks reentrant initialization from a callback.
        state = State.FAILED
        failureCode = "initialization_failed"
        try {
            action()
            state = State.READY
            failureCode = null
        } catch (_: LinkageError) {
            failureCode = "native_library_unavailable"
        } catch (_: RuntimeException) {
            failureCode = "initialization_failed"
        }
        return state == State.READY
    }
}

/** Independent of Android/JNI, so malformed and noncanonical packages can be tested on the JVM. */
internal object BotMascotPlaybackVerifier {
    fun verify(source: ByteArray, riv: ByteArray): ByteArray {
        require(riv.size in 8..BotMascotSceneCompiler.MAX_OUTPUT_BYTES) { "Invalid mascot asset size" }
        val expected = BotMascotSceneCompiler.compileProduct(source).bytes
        require(MessageDigest.isEqual(expected, riv)) { "Mascot asset does not match its local source" }
        return expected
    }
}
