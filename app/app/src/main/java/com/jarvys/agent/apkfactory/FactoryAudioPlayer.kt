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

/** Local, one-shot playback. No native operation is claimed to have a hard realtime bound. */
class FactoryAudioPlayer internal constructor(private val platform: Platform) {
    constructor(context: Context) : this(AndroidPlatform(context.applicationContext))

    data class StopResult(val cleanupConfirmed: Boolean, val reason: String)
    class Prepared internal constructor(internal val track: Track, internal val pcm: FactoryAudioPcm) {
        var preparationFailure: String? = null
            internal set
        internal var attempted = false
        internal var released = false
    }
    internal interface Track {
        fun write(pcm: FactoryAudioPcm): Int
        fun marker(frames: Int, callback: () -> Unit)
        fun play()
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

    /** Run on a worker. Allocates STATIC storage and performs exactly one write, never plays. */
    fun prepare(pcm: FactoryAudioPcm): Prepared {
        platform.requireWorker()
        val track = try { platform.create(pcm) } catch (_: Exception) {
            return Prepared(object : Track {
                override fun write(pcm: FactoryAudioPcm) = 0
                override fun marker(frames: Int, callback: () -> Unit) { }
                override fun play() { error("No native track") }
                override fun stop() { }
                override fun release() { }
            }, pcm).apply { preparationFailure = "Native PCM allocation failed" }
        }
        val prepared = Prepared(track, pcm)
        try {
            if (prepared.track.write(pcm) != pcm.pcmBytes) prepared.preparationFailure = "Incomplete native PCM write"
        } catch (_: Exception) {
            prepared.preparationFailure = "Native PCM write failed"
        }
        return prepared
    }

    /** Takes ownership even on refusal. [isStillAllowed] must check foreground, guard and generation. */
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
            prepared.track.play()
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
        current = null
        timer?.let { try { platform.cancel(it); timer = null } catch (_: Exception) { } }
        retained.toList().forEach(::cleanupTrack)
        if (routesNeedCleanup) try { platform.unobserveRoutes(); routesNeedCleanup = false } catch (_: Exception) { }
        if (focusNeedsCleanup) try { platform.abandonFocus(); focusNeedsCleanup = false } catch (_: Exception) { }
        val result = StopResult(retained.isEmpty() && !focusNeedsCleanup && !routesNeedCleanup && timer == null, reason)
        val notify = callback
        callback = null
        stopping = false
        notify?.invoke(result)
        return result
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
            val native = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.pcmBytes).build()
            return object : Track {
                private var routing: AudioRouting.OnRoutingChangedListener? = null
                override fun write(pcm: FactoryAudioPcm): Int {
                    val count = native.write(pcm.bytes, pcm.pcmOffset, pcm.pcmBytes)
                    check(native.state == AudioTrack.STATE_INITIALIZED) { "Native PCM track is not initialized" }
                    return count
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
                        // A STATIC track can be unrouted before play. Its first assignment is
                        // not a change; after that, route loss or replacement is terminal.
                        if (initialRoute == null && route != null) initialRoute = route
                        else if (route == null || route != initialRoute) callback()
                    }
                    routing = listener
                    native.addOnRoutingChangedListener(listener, handler)
                }
                override fun play() = native.play()
                override fun stop() {
                    // MODE_STATIC is STATE_NO_STATIC_DATA until the write succeeds.
                    if (native.state == AudioTrack.STATE_INITIALIZED && native.playState != AudioTrack.PLAYSTATE_STOPPED) native.stop()
                }
                override fun release() {
                    var failure: Exception? = null
                    try { native.setPlaybackPositionUpdateListener(null) } catch (e: Exception) { failure = e }
                    routing?.let { listener ->
                        try { native.removeOnRoutingChangedListener(listener); routing = null } catch (e: Exception) { failure = e }
                    }
                    try { native.release() } catch (e: Exception) { failure = e }
                    failure?.let { throw it }
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
