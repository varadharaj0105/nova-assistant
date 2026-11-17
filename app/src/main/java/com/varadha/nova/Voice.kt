package com.varadha.nova

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Listening: turns speech into text, with live partial results. */
class Listener(private val c: Context, private val events: Events) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Suppress("UnusedReceiverParameter")
    private fun SpeechRecognizer.handler() = main

    interface Events {
        fun onListening()
        fun onLevel(level: Float) // 0..1, for the mic animation
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onListenError(message: String)
        /** The built-in speech service didn't work on this phone: use the standard voice popup instead. */
        fun onNeedFallback(reason: String)
    }

    private var ready = false
    private val watchdog = Runnable {
        if (!ready && recognizer != null) {
            stop()
            events.onNeedFallback("speech service didn't start")
        }
    }

    private var recognizer: SpeechRecognizer? = null
    var active = false
        private set

    val available get() = SpeechRecognizer.isRecognitionAvailable(c)

    fun start(language: String?) {
        stop()
        ready = false
        // Use Google's recogniser directly (when Nova is the phone's assistant, the default points back at Nova).
        val google = Assistant.googleRecognizer(c)
        val r = runCatching {
            if (google != null) SpeechRecognizer.createSpeechRecognizer(c, google)
            else SpeechRecognizer.createSpeechRecognizer(c)
        }.getOrElse {
            events.onNeedFallback("couldn't create speech service")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                main.removeCallbacks(watchdog)
                active = true
                events.onListening()
            }

            override fun onRmsChanged(rmsdB: Float) = events.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(events::onPartial)
            }

            override fun onResults(results: Bundle?) {
                active = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) events.onListenError("Didn't catch that") else events.onFinal(text)
            }

            override fun onError(error: Int) {
                active = false
                main.removeCallbacks(watchdog)
                // Language not available on this phone's speech engine: retry once in the phone's own language.
                if ((error == 12 || error == 13) && language != null) {
                    r.handler().post { start(null) }
                    return
                }
                // Problems with the speech service itself (not with what you said): use the voice popup.
                if (error in SERVICE_ERRORS || error == 12 || error == 13) {
                    stop()
                    events.onNeedFallback("speech service error $error")
                    return
                }
                events.onListenError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for speech"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Microphone is busy, try again"
                        else -> "Couldn't listen (error $error)"
                    }
                )
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .apply { if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
        main.postDelayed(watchdog, 4000)
    }

    fun stop() {
        main.removeCallbacks(watchdog)
        active = false
        recognizer?.run {
            runCatching { cancel() }
            destroy()
        }
        recognizer = null
    }

    companion object {
        /** client, server, busy, too many requests, server disconnected */
        private val SERVICE_ERRORS = setOf(
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 10, 11
        )
    }
}

/** Speaking replies aloud, in Indian English or Tamil. */
class Speaker(c: Context, private val onDone: () -> Unit) {
    private var ready = false
    private val tts: TextToSpeech

    init {
        var created: TextToSpeech? = null
        created = TextToSpeech(c.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) created?.language = Locale("en", "IN")
        }
        tts = created!!
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = onDone()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = onDone()
        })
    }

    fun say(text: String) {
        if (!ready || text.isBlank()) return onDone()
        val tamil = text.any { it in '஀'..'௿' }
        tts.language = if (tamil) Locale("ta", "IN") else Locale("en", "IN")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
