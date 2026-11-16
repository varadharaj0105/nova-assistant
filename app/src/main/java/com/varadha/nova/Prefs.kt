package com.varadha.nova

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Settings and memory. Everything stays on the phone (app-private storage). */
object Prefs {
    private const val FILE = "nova"
    private const val K_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_VOICE = "voice_replies"
    private const val K_NAME = "assistant_name"
    private const val K_USER = "user_name"
    private const val K_MEMORY = "memory"
    private const val K_LANG = "listen_lang"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(c: Context) = prefs(c).getString(K_KEY, "").orEmpty()
    fun setApiKey(c: Context, k: String) =
        prefs(c).edit().putString(K_KEY, k.trim()).remove(K_MODEL).apply()

    /** The Gemini model picked for this key (found automatically). */
    fun model(c: Context) = prefs(c).getString(K_MODEL, null)
    fun setModel(c: Context, m: String?) = prefs(c).edit().putString(K_MODEL, m).apply()

    fun voiceReplies(c: Context) = prefs(c).getBoolean(K_VOICE, true)
    fun setVoiceReplies(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_VOICE, on).apply()

    fun assistantName(c: Context) = prefs(c).getString(K_NAME, "Nova").orEmpty().ifBlank { "Nova" }
    fun setAssistantName(c: Context, n: String) = prefs(c).edit().putString(K_NAME, n.trim()).apply()

    fun userName(c: Context) = prefs(c).getString(K_USER, "").orEmpty()
    fun setUserName(c: Context, n: String) = prefs(c).edit().putString(K_USER, n.trim()).apply()

    /** Speech language: "en-IN" (English, understands Tanglish) or "ta-IN" (Tamil). */
    fun listenLang(c: Context) = prefs(c).getString(K_LANG, "en-IN").orEmpty()
    fun setListenLang(c: Context, l: String) = prefs(c).edit().putString(K_LANG, l).apply()

    // ---- memory: short facts the user asked Nova to remember ----

    fun memory(c: Context): List<String> {
        val raw = prefs(c).getString(K_MEMORY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun setMemory(c: Context, list: List<String>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        prefs(c).edit().putString(K_MEMORY, a.toString()).apply()
    }

    fun remember(c: Context, fact: String) {
        val f = fact.trim()
        if (f.isNotEmpty()) setMemory(c, memory(c).filterNot { it.equals(f, true) } + f)
    }

    /** Removes facts containing [text]. Returns how many were removed. */
    fun forget(c: Context, text: String): Int {
        val before = memory(c)
        val after = before.filterNot { it.contains(text.trim(), ignoreCase = true) }
        setMemory(c, after)
        return before.size - after.size
    }
}
