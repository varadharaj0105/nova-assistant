package com.varadha.nova

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Lets Nova be the phone's "digital assistant": long-press power (or the corner swipe, or the
 * earphone button) opens Nova already listening. Nothing runs in the background; the phone
 * starts Nova only when you press.
 */
object Assistant {

    /** Is Nova currently the phone's assistant? */
    fun isDefault(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val current = android.provider.Settings.Secure.getString(c.contentResolver, "assistant").orEmpty()
        return current.startsWith(c.packageName)
    }

    /** Opens the screen where you pick the default assistant. */
    fun openSettings(c: Context) {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /**
     * Google's own speech recogniser (not Nova's pass-through), so listening never loops back
     * into Nova. Null if the phone has none.
     */
    fun googleRecognizer(c: Context): ComponentName? {
        val services = c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != c.packageName }
        return services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") }
            ?: services.firstOrNull()
    }
}

/** Required by Android for an assistant app. Nova needs nothing running here. */
class NovaInteractionService : VoiceInteractionService()

/** Creates a session each time you long-press power. */
class NovaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NovaSession(this)
}

/** A session simply opens Nova listening (works on the lock screen too). */
class NovaSession(c: Context) : VoiceInteractionSession(c) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startAssistantActivity(intent) }
            .onFailure { runCatching { context.startActivity(intent) } }
        hide()
    }
}

/**
 * When Nova is the assistant, Android also sends other apps' voice typing here.
 * This passes every request straight to Google's recogniser and passes the results back,
 * so voice typing everywhere keeps working exactly as before.
 */
class NovaRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        val target = Assistant.googleRecognizer(this)
        if (target == null) {
            runCatching { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { runCatching { cb.readyForSpeech(params ?: Bundle()) } }
                override fun onBeginningOfSpeech() { runCatching { cb.beginningOfSpeech() } }
                override fun onRmsChanged(rmsdB: Float) { runCatching { cb.rmsChanged(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) { runCatching { cb.bufferReceived(buffer) } }
                override fun onEndOfSpeech() { runCatching { cb.endOfSpeech() } }
                override fun onError(error: Int) { runCatching { cb.error(error) } }
                override fun onResults(results: Bundle?) { runCatching { cb.results(results ?: Bundle()) } }
                override fun onPartialResults(partial: Bundle?) { runCatching { cb.partialResults(partial ?: Bundle()) } }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(intent)
        }
    }

    override fun onStopListening(cb: Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(cb: Callback) {
        recognizer?.cancel()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
