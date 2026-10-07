package com.jarvys.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** One Activity-owned TTS engine; speaking another response flushes/stops the prior one. */
internal class AssistantSpeechController(
    context: Context,
    private val onSpeakingChanged: (String?) -> Unit,
    private val onUnavailable: () -> Unit,
) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = TextToSpeech(context.applicationContext, this)
    private var initialized = false
    private var initComplete = false
    private var requested = false
    private var activeMessageId: String? = null
    private var queued: Pair<String, String>? = null

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?) = finish(utteranceId)
        })
    }

    override fun onInit(status: Int) {
        initComplete = true
        initialized = status == TextToSpeech.SUCCESS
        val next = queued
        queued = null
        if (initialized && next != null) speakNow(next.first, next.second)
        else if (!initialized && requested) {
            activeMessageId = null
            onSpeakingChanged(null)
            onUnavailable()
        }
    }

    fun speak(messageId: String, markdown: String) {
        stop()
        val clean = assistantSpeechText(markdown)
        if (clean.isEmpty()) return
        requested = true
        if (initComplete && !initialized) {
            onUnavailable()
            return
        }
        activeMessageId = messageId
        onSpeakingChanged(messageId)
        if (initialized) speakNow(messageId, clean) else queued = messageId to clean
    }

    fun stop(messageId: String? = null) {
        if (activeMessageId == null || (messageId != null && activeMessageId != messageId)) return
        queued = null
        requested = false
        activeMessageId = null
        engine.stop()
        onSpeakingChanged(null)
    }

    fun shutdown() {
        stop()
        engine.shutdown()
    }

    private fun speakNow(messageId: String, text: String) {
        if (activeMessageId != messageId) return
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, messageId)
        if (result == TextToSpeech.ERROR) finish(messageId)
    }

    private fun finish(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId == activeMessageId) {
                activeMessageId = null
                onSpeakingChanged(null)
            }
        }
    }
}
