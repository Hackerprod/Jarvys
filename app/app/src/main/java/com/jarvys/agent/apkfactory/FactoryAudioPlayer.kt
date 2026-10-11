package com.jarvys.agent.apkfactory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local, one-shot playback. No native operation is claimed to have a hard realtime bound. */
class FactoryAudioPlayer internal constructor(private val platform: Platform) {
    constructor(context: Context) : this(AndroidPlatform(context.applicationContext))

    data class StopResult(val cleanupConfirmed: Boolean, val reason: String)
    class Prepared internal constructor(internal val track: Track, internal val pcm: FactoryAudioPcm) {
        var preparationFailure: String? = null
            internal set
        internal var attempted = false
        internal var released = false
        internal var awaitingFeeder = false
    }
    internal interface Track {
        fun prepare()
        /** True only when no queued/running feeder can touch native or disk resources. */
        fun quiescent(onReady: () -> Unit): Boolean = true
        fun marker(frames: Int, callback: () -> Unit)
        fun play(onFailure: () -> Unit)
        fun stop()
        fun release()
    }
    internal interface Platform {
        fun requireMain()
        fun requireWorker()
        fun create(pcm: FactoryAudioPcm): Track
        fun acquireFocus(onLoss: () -> Unit): Boolean
        fun abandonFocus()
        fun observeRoutes(onChange: () -> Unit)
        fun unobserveRoutes()
        fun now(): Long
        fun schedule(delayMs: Long, callback: () -> Unit): Any
        fun cancel(token: Any)
    }
    private var current: Prepared? = null
    private val retained = linkedSetOf<Prepared>()
    private var callback: ((StopResult) -> Unit)? = null
    private var timer: Any? = null
    private var focusNeedsCleanup = false
    private var routesNeedCleanup = false
    private var stopping = false
    private var stopReason = "Playback stopped; audible outcome unknown"

    /** Worker-only native allocation and bounded prefill. Never starts playback. */
    fun prepare(pcm: FactoryAudioPcm): Prepared {
        platform.requireWorker()
        val track = try { platform.create(pcm) } catch (_: Exception) {
            return Prepared(object : Track {
                override fun prepare() { }
                override fun marker(frames: Int, callback: () -> Unit) { }
                override fun play(onFailure: () -> Unit) { error("No native track") }
                override fun stop() { }
                override fun release() { }
            }, pcm).apply { preparationFailure = "Native PCM allocation failed" }
        }
        val prepared = Prepared(track, pcm)
        try {
            prepared.track.prepare()
        } catch (_: Exception) {
            prepared.preparationFailure = "Native PCM write failed"
        }
        return prepared
    }

    /** Takes ownership even on refusal. [isStillAllowed] must check foreground, policy grant and generation. */
    fun start(prepared: Prepared, isStillAllowed: () -> Boolean, onStopped: (StopResult) -> Unit): Boolean {
        platform.requireMain()
        if (current != null || retained.isNotEmpty() || focusNeedsCleanup || routesNeedCleanup) {
            val result = discard(prepared)
            onStopped(result.copy(reason = "Player unavailable; no playback attempted"))
            return false
        }
        current = prepared
        retained.add(prepared)
        callback = onStopped
        if (prepared.attempted || prepared.released || prepared.preparationFailure != null) {
            stopWithReason("Prepared audio unavailable")
            return false
        }
        prepared.attempted = true
        try {
            if (!isStillAllowed()) { stopWithReason("Playback authorization expired"); return false }
            focusNeedsCleanup = true
            if (!platform.acquireFocus { if (current === prepared) stopWithReason("Audio focus lost") }) {
                stopWithReason("Audio focus unavailable")
                return false
            }
            if (current !== prepared) return false
            routesNeedCleanup = true
            platform.observeRoutes { if (current === prepared) stopWithReason("Audio route changed") }
            if (current !== prepared) return false
            prepared.track.marker(prepared.pcm.frames) { if (current === prepared) stopWithReason("Playback stopped; audible outcome unknown") }
            if (!isStillAllowed() || current !== prepared) { stopWithReason("Playback authorization expired"); return false }
            val deadline = platform.now() + prepared.pcm.durationMs
            prepared.track.play { if (current === prepared) stopWithReason("Native streaming failed; audible outcome unknown") }
            if (current !== prepared) return false
            fun armDeadline() {
                val remaining = deadline - platform.now()
                if (remaining <= 0) stopWithReason("Playback deadline reached; audible outcome unknown")
                else timer = platform.schedule(remaining) { if (current === prepared) armDeadline() }
            }
            armDeadline()
            return current === prepared
        } catch (_: Exception) {
            stopWithReason("Native playback failed; audible outcome unknown")
            return false
        }
    }

    fun stop(): StopResult { platform.requireMain(); return stopWithReason("Playback stopped; audible outcome unknown") }
    fun retryCleanup(): StopResult { platform.requireMain(); return stopWithReason("Cleanup retried; audible outcome unknown") }

    /** Disposes a late worker result without playing it; failed cleanup keeps its native reference. */
    fun discard(prepared: Prepared): StopResult {
        platform.requireMain()
        prepared.attempted = true
        if (!prepared.released) retained.add(prepared)
        if (current === prepared) return stop()
        cleanupTrack(prepared)
        return StopResult(retained.isEmpty() && !focusNeedsCleanup && !routesNeedCleanup, "Unused audio discarded")
    }

    private fun cleanupTrack(prepared: Prepared) {
        if (prepared.released) { retained.remove(prepared); return }
        // Even when stop fails, attempt release. A stop failure is conservatively unconfirmed
        // for this attempt; the retained reference is available to explicit cleanup retry.
        var stopped = true
        try { prepared.track.stop() } catch (_: Exception) { stopped = false }
        prepared.awaitingFeeder = !prepared.track.quiescent {
            if (!prepared.released) stopWithReason(stopReason)
        }
        if (prepared.awaitingFeeder) return
        try {
            prepared.track.release()
            prepared.released = true
            if (stopped) retained.remove(prepared)
        } catch (_: Exception) { /* Retain handle and durable admission guard. */ }
    }

    private fun stopWithReason(reason: String): StopResult {
        platform.requireMain()
        if (stopping) return StopResult(false, reason)
        stopping = true
        stopReason = reason
        current = null
        timer?.let { try { platform.cancel(it); timer = null } catch (_: Exception) { } }
        retained.toList().forEach(::cleanupTrack)
        if (routesNeedCleanup) try { platform.unobserveRoutes(); routesNeedCleanup = false } catch (_: Exception) { }
        if (focusNeedsCleanup) try { platform.abandonFocus(); focusNeedsCleanup = false } catch (_: Exception) { }
        val result = StopResult(retained.isEmpty() && !focusNeedsCleanup && !routesNeedCleanup && timer == null, reason)
        // A queued or blocked feeder still owns the snapshot. Do not issue a terminal callback
        // or permit coordinator cleanup until its actual completion callback has run.
        val waiting = retained.any { it.awaitingFeeder }
        val notify = if (waiting) null else callback
        if (!waiting) callback = null
        stopping = false
        notify?.invoke(result)
        return result
    }

    companion object {
        // Shared single feeder: a stalled disk/native operation retains capacity; no replacement.
        private val FEEDER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "factory-audio-stream").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    private class AndroidPlatform(private val context: Context) : Platform {
        private val handler = Handler(Looper.getMainLooper())
        private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
        private var focusRequest: AudioFocusRequest? = null
        private var receiver: BroadcastReceiver? = null
        private var devices: AudioDeviceCallback? = null
        private val attributes: AudioAttributes get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply { if (Build.VERSION.SDK_INT >= 29) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE) }.build()

        override fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Playback control requires main thread" } }
        override fun requireWorker() { check(Looper.myLooper() != Looper.getMainLooper()) { "PCM preparation requires worker thread" } }
        override fun create(pcm: FactoryAudioPcm): Track {
            val channelMask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(pcm.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "Unsupported native PCM format" }
            // Tiny clips cannot fill older Android streaming start thresholds. Preserve their
            // static path using at most the same fixed 32 KiB working buffer, never a large array.
            val shortClip = pcm.pcmBytes <= 32 * 1024
            val native = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(channelMask).build())
                .setTransferMode(if (shortClip) AudioTrack.MODE_STATIC else AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(if (shortClip) pcm.pcmBytes.toInt() else maxOf(minimum, 32 * 1024)).build()
            return object : Track {
                private val lock = Any()
                private var cancelled = false
                private var released = false
                private var feeding = false
                private var ready: (() -> Unit)? = null
                private var routing: AudioRouting.OnRoutingChangedListener? = null
                private val feeder = FactoryAudioFeeder(pcm, { bytes, offset, count ->
                    synchronized(lock) {
                        if (cancelled || released) 0
                        else native.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                    }
                })
                override fun prepare() {
                    feeder.prime(requireComplete = shortClip)
                    check(native.state == AudioTrack.STATE_INITIALIZED) { "Native PCM track is not initialized" }
                    if (!shortClip && Build.VERSION.SDK_INT >= 31) native.setStartThresholdInFrames(1)
                }
                override fun marker(frames: Int, callback: () -> Unit) {
                    native.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack) = callback()
                        override fun onPeriodicNotification(track: AudioTrack) { }
                    }, handler)
                    check(native.setNotificationMarkerPosition(frames) == AudioTrack.SUCCESS)
                    var initialRoute = native.routedDevice?.id
                    val listener = AudioRouting.OnRoutingChangedListener {
                        val route = native.routedDevice?.id
                        // A track may be unrouted before play. Only its first assignment is ignored.
                        if (initialRoute == null && route != null) initialRoute = route
                        else if (route == null || route != initialRoute) callback()
                    }
                    routing = listener
                    native.addOnRoutingChangedListener(listener, handler)
                }
                override fun play(onFailure: () -> Unit) {
                    synchronized(lock) { check(!cancelled && !released && !feeding); native.play(); if (!shortClip) feeding = true }
                    if (shortClip) return
                    try {
                        FEEDER.execute {
                            var failed = false
                            try { feeder.run() } catch (_: Exception) { failed = true }
                            finally {
                                val notify = synchronized(lock) { feeding = false; ready.also { ready = null } }
                                handler.post { if (failed) onFailure(); notify?.invoke() }
                            }
                        }
                    } catch (failure: RuntimeException) {
                        synchronized(lock) { feeding = false }
                        throw failure
                    }
                }
                override fun stop() {
                    feeder.cancel()
                    synchronized(lock) {
                        cancelled = true
                        if (!released && native.state == AudioTrack.STATE_INITIALIZED && native.playState != AudioTrack.PLAYSTATE_STOPPED) {
                            if (!shortClip) { native.pause(); native.flush() }
                            native.stop()
                        }
                    }
                }
                override fun quiescent(onReady: () -> Unit): Boolean = synchronized(lock) {
                    if (feeding) { ready = onReady; false } else true
                }
                override fun release() {
                    synchronized(lock) {
                        check(!feeding)
                        var failure: Exception? = null
                        try { native.setPlaybackPositionUpdateListener(null) } catch (e: Exception) { failure = e }
                        routing?.let { listener ->
                            try { native.removeOnRoutingChangedListener(listener); routing = null } catch (e: Exception) { failure = e }
                        }
                        try { native.release(); released = true } catch (e: Exception) { failure = e }
                        feeder.discard()
                        failure?.let { throw it }
                    }
                }
            }
        }
        override fun acquireFocus(onLoss: () -> Unit): Boolean {
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (Looper.myLooper() == Looper.getMainLooper()) onLoss() else handler.post { onLoss() }
                }
            }
            focusListener = listener
            return if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(listener, handler).build()
                focusRequest = request
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        override fun abandonFocus() {
            val result = if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) manager.abandonAudioFocusRequest(focusRequest!!)
                else focusListener?.let { manager.abandonAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focusRequest = null
            focusListener = null
        }
        override fun observeRoutes(onChange: () -> Unit) {
            val baseline = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
            val routeReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onChange() } }
            receiver = routeReceiver
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(routeReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(routeReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            val deviceCallback = object : AudioDeviceCallback() {
                fun changed() { if (manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet() != baseline) onChange() }
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
            }
            devices = deviceCallback
            manager.registerAudioDeviceCallback(deviceCallback, handler)
        }
        override fun unobserveRoutes() {
            var failed = false
            receiver?.let { try { context.unregisterReceiver(it); receiver = null } catch (_: Exception) { failed = true } }
            devices?.let { try { manager.unregisterAudioDeviceCallback(it); devices = null } catch (_: Exception) { failed = true } }
            check(!failed)
        }
        override fun now() = SystemClock.elapsedRealtime()
        override fun schedule(delayMs: Long, callback: () -> Unit): Any = Runnable { callback() }.also { check(handler.postDelayed(it, delayMs)) }
        override fun cancel(token: Any) { handler.removeCallbacks(token as Runnable) }
    }
}
