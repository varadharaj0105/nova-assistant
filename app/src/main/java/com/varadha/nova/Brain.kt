package com.varadha.nova

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The conversation: sends what you said to Gemini, lets Gemini use phone actions (tools),
 * and returns the final reply. Keeps a short history so follow-ups like "make it 7 instead" work.
 */
class Brain(private val c: Context, private val tools: Tools) {

    private val history = mutableListOf<JSONObject>()

    fun reset() = synchronized(history) { history.clear() }

    /** Blocking (network): call off the main thread. Returns the spoken reply. */
    fun ask(text: String): String = synchronized(history) {
        val key = Prefs.apiKey(c)
        if (key.isBlank()) return "Add your free Gemini API key in Settings first."
        val saved = Prefs.model(c)
        // Older versions could pick a "preview" model, which is often busy on the free tier: re-pick.
        val model = if (saved == null || saved.contains("preview") || saved.contains("exp"))
            Gemini.pickModel(key).also { Prefs.setModel(c, it) } else saved

        history += JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

        try {
            repeat(MAX_STEPS) {
                val content = callModel(key, model)
                history += content.put("role", "model")
                val parts = content.optJSONArray("parts") ?: JSONArray()

                val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
                val reply = (0 until parts.length())
                    .map { parts.getJSONObject(it) }
                    .filter { !it.optBoolean("thought") && it.has("text") }
                    .joinToString(" ") { it.getString("text") }
                    .trim()

                if (calls.isEmpty()) {
                    trim()
                    return reply.ifBlank { "Done." }
                }

                // Run each action and tell Gemini how it went.
                val responses = JSONArray()
                calls.forEach { call ->
                    val name = call.optString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    val result = runCatching { tools.run(name, args) }
                        .getOrElse { JSONObject().put("error", it.message ?: "failed") }
                    responses.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", name).put("response", result)
                        )
                    )
                }
                history += JSONObject().put("role", "user").put("parts", responses)
            }
            trim()
            "Done."
        } catch (e: Gemini.ApiError) {
            // Undo the half-finished turn so the next question starts clean.
            while (history.isNotEmpty() && !isUserText(history.last())) history.removeAt(history.size - 1)
            if (history.isNotEmpty()) history.removeAt(history.size - 1)
            e.message ?: "Something went wrong."
        }
    }

    /**
     * Calls Gemini. If Google is busy (5xx) it retries once; if the model is overloaded, out of
     * free quota (429) or gone (404), it switches to the next-best free model and remembers it.
     */
    private fun callModel(key: String, model: String): JSONObject {
        var current = model
        val tried = mutableSetOf<String>()
        var last: Gemini.ApiError? = null
        repeat(4) { attempt ->
            try {
                val result = Gemini.generate(key, current, systemPrompt(), JSONArray(history), tools.declarations)
                if (current != Prefs.model(c)) Prefs.setModel(c, current)
                Prefs.setLastError(c, null)
                return result
            } catch (e: Gemini.ApiError) {
                last = e
                Prefs.setLastError(c, "$current: ${e.message}")
                if (e.code !in RETRYABLE) throw e
                if (attempt == 0 && e.code >= 500) {
                    Thread.sleep(1200) // short hiccup: same model once more
                    return@repeat
                }
                tried += current
                current = runCatching { Gemini.pickModel(key, avoid = tried) }.getOrElse { throw e }
            }
        }
        throw last ?: Gemini.ApiError("Gemini didn't answer", 500)
    }

    private fun isUserText(o: JSONObject): Boolean {
        if (o.optString("role") != "user") return false
        val p = o.optJSONArray("parts") ?: return false
        return p.length() > 0 && p.getJSONObject(0).has("text")
    }

    /** Keeps the last ~12 exchanges, always starting at something you said. */
    private fun trim() {
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
            while (history.isNotEmpty() && !isUserText(history.first())) history.removeAt(0)
        }
    }

    private fun systemPrompt(): String {
        val name = Prefs.assistantName(c)
        val user = Prefs.userName(c).ifBlank { "the user" }
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val zone = TimeZone.getDefault().id
        val memory = Prefs.memory(c)
        val battery = batteryText()
        return buildString {
            appendLine("You are $name, a friendly voice assistant on $user's Android phone in India.")
            appendLine("Now: $now ($zone). $battery")
            if (memory.isNotEmpty()) {
                appendLine("Things $user asked you to remember:")
                memory.forEach { appendLine("- $it") }
            }
            appendLine(
                """
                How to behave:
                - Use the tools to do things on the phone. Act right away; only ask a question if something essential is missing.
                - Reply in the language the user used: English, Tamil, or Tanglish (Tamil written in English letters). Match their style.
                - Your reply is read aloud: 1-2 short sentences, no markdown, no emojis, no lists unless asked.
                - Tool times use 24-hour clock. Pick today or tomorrow sensibly ("at 6" in the evening means 18:00 if 6 AM has passed).
                - For "remind me", use set_reminder. For "wake me up", use set_alarm.
                - If the user shares a lasting personal fact (a UPI ID, who someone is, a preference), call remember.
                - UPI payments need the payee's UPI ID: use memory, otherwise ask for it.
                - For general questions, just answer briefly from your knowledge.
                """.trimIndent()
            )
        }
    }

    private fun batteryText(): String {
        val i: Intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0) return ""
        return "Phone battery: ${level * 100 / scale}%${if (plugged) ", charging" else ""}."
    }

    companion object {
        private const val MAX_STEPS = 5
        private const val MAX_HISTORY = 24
        private val RETRYABLE = setOf(404, 429, 500, 502, 503, 504)
    }
}
