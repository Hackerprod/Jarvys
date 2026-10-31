package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bundled source/binary integrity; never loads Rive JNI or claims device rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvysMascotAssetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun asset(name: String) = context.assets.open("bot_mascots/jarvys/$name").use { it.readBytes() }

    @Test fun originalSourceCompilesExactlyToThePackagedBinary() {
        val source = asset("source.json")
        val binary = asset("asset.riv")
        assertEquals(41445, source.size)
        assertEquals(18237, binary.size)
        val validated = JarvysMascotAssets.validate(source, binary)
        assertArrayEquals(binary, validated)
        val compiled = BotMascotSceneCompiler.compileProduct(source)
        assertEquals(JarvysMascotAssets.ASSET_SHA256, compiled.sha256)
        assertEquals(61, compiled.validation.complexity.nodeCount)
        assertEquals(872, compiled.validation.complexity.keyframeCount)
    }

    @Test fun rejectsAlteredSourceAndAlteredBinaryBeforeNativeImport() {
        val source = asset("source.json")
        val binary = asset("asset.riv")
        val badSource = source.clone().also { it[0] = 0 }
        val badBinary = binary.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { JarvysMascotAssets.validate(badSource, binary) }
        assertThrows(IllegalArgumentException::class.java) { JarvysMascotAssets.validate(source, badBinary) }
    }

    @Test fun rejectsEmptyOversizedAndForeignBytes() {
        assertThrows(IllegalArgumentException::class.java) { JarvysMascotAssets.validate(byteArrayOf(), byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { JarvysMascotAssets.validate(ByteArray(131073), ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { JarvysMascotAssets.validate(asset("source.json"), ByteArray(65537)) }
    }

    @Test fun asyncLoadsReuseOnlyAnImmutablePrivateCacheAndReturnOwnedArrays() = runBlocking {
        val first = JarvysMascotAssets.load(context)
        val second = JarvysMascotAssets.load(context)
        assertArrayEquals(first, second)
        assertNotSame(first, second)
        first[0] = 0
        assertEquals('R'.code.toByte(), JarvysMascotAssets.load(context)[0])
    }
}
