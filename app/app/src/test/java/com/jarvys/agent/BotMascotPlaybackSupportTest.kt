package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BotMascotPlaybackSupportTest {
    private fun source(name: String = "nimbo") = requireNotNull(javaClass.getResourceAsStream("/bot-mascot-scenes/$name.json")).use { it.readBytes() }

    @Test fun acceptsBothOriginalProductPackagesAndReturnsOwnedBytes() {
        for (name in listOf("nimbo", "folio")) {
            val source = source(name)
            val riv = BotMascotSceneCompiler.compileProduct(source).bytes
            val verified = BotMascotPlaybackVerifier.verify(source, riv)
            assertArrayEquals(riv, verified)
            assertNotSame(riv, verified)
            riv[0] = 0
            assertEquals('R'.code.toByte(), verified[0])
        }
    }

    @Test fun rejectsCorruptionEvenIfAnAttackerRecomputedTheStoredHash() {
        val source = source()
        val riv = BotMascotSceneCompiler.compileProduct(source).bytes
        riv[riv.lastIndex] = (riv.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { BotMascotPlaybackVerifier.verify(source, riv) }
    }

    @Test fun rejectsAnotherValidMascotForThisSource() {
        assertThrows(IllegalArgumentException::class.java) {
            BotMascotPlaybackVerifier.verify(source(), BotMascotSceneCompiler.compileProduct(source("folio")).bytes)
        }
    }

    @Test fun rejectsEmptyOversizeAndNonProductSource() {
        for (bad in listOf(byteArrayOf(), ByteArray(BotMascotSceneCompiler.MAX_OUTPUT_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { BotMascotPlaybackVerifier.verify(source(), bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotMascotPlaybackVerifier.verify("{}".toByteArray(), "RIVE0000".toByteArray())
        }
    }

    @Test fun initializationHasNoSideEffectsUntilExplicitInvocationAndRunsOnlyOnce() {
        val gate = BotMascotNativeInitGate()
        var calls = 0
        assertNull(gate.failureCode)
        assertTrue(gate.initialize { calls++ })
        assertTrue(gate.initialize { calls++ })
        assertEquals(1, calls)
        assertNull(gate.failureCode)
    }

    @Test fun cachesNativeAndOrdinaryFailureWithoutLeakingExceptionText() {
        for (native in listOf(false, true)) {
            val gate = BotMascotNativeInitGate()
            var calls = 0
            assertFalse(gate.initialize {
                calls++
                if (native) throw UnsatisfiedLinkError("private diagnostic") else throw IllegalStateException("private diagnostic")
            })
            assertFalse(gate.initialize { calls++ })
            assertEquals(1, calls)
            assertEquals(if (native) "native_library_unavailable" else "initialization_failed", gate.failureCode)
        }
    }

    @Test fun serializesConcurrentInitializationAndRejectsReentrantAttempt() {
        val gate = BotMascotNativeInitGate()
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map {
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.initialize {
                        calls.incrementAndGet()
                        assertFalse(gate.initialize { fail("reentrant initializer") })
                    }
                }
            }
            start.countDown()
            results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { executor.shutdownNow() }
    }
}
