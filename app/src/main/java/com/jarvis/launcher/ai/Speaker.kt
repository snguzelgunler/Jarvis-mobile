package com.jarvis.launcher.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Offline text-to-speech with a preference for a British male voice. */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var ready = false
    private var tts: TextToSpeech? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                configure()
            }
        }
    }

    private fun configure() {
        val t = tts ?: return
        val gb = Locale.UK
        if (t.isLanguageAvailable(gb) >= TextToSpeech.LANG_AVAILABLE) t.language = gb
        
        t.setPitch(0.78f)
        t.setSpeechRate(0.92f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false; fire(id) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false; fire(id) }
            override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false; fire(id) }
        })
    }

    private fun fire(id: String?) {
        val cb = id?.let { callbacks.remove(it) } ?: return
        main.post(cb)
    }

    /** Speaks [text]; [onDone] runs on the main thread when speech finishes (or immediately if TTS is unavailable). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (!ready || clean.isEmpty()) { onDone?.let { main.post(it) }; return }
        val id = "jarvis-${System.nanoTime()}"
        if (onDone != null) callbacks[id] = onDone
        // Flushing the queue stops the previous utterance; release its waiter.
        val pending = callbacks.keys.filter { it != id }
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
        pending.forEach { fire(it) }
        if (result != TextToSpeech.SUCCESS) fire(id)
    }

    fun stop() {
        tts?.stop()
        _speaking.value = false
        callbacks.keys.toList().forEach { fire(it) }
    }
}
