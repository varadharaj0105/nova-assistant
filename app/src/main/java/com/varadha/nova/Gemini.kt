package com.varadha.nova

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Google's Gemini API with the user's own key.
 * The key is sent in a header (never in the URL) and only to Google.
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    class ApiError(message: String, val code: Int) : Exception(message)

    /**
     * Asks Google which models this key can use and picks the best free "Flash" model.
     * Google renames models often, so this is never hard-coded.
     */
    fun pickModel(key: String, avoid: Set<String> = emptySet()): String =
        models(key).firstOrNull { it !in avoid }
            ?: throw ApiError("No working Gemini model found for this key", 404)

    /** Chat-capable Flash models for this key, best first. */
    fun models(key: String): List<String> {
        val json = request("GET", "$BASE/models?pageSize=200", key, null)
        val models = json.optJSONArray("models") ?: JSONArray()
        return (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
            if (!methods.contains("generateContent")) return@mapNotNull null
            m.optString("name").removePrefix("models/")
        }.filter { score(it) > 0 }.sortedByDescending { score(it) }
    }

    /** Higher = better: stable before preview (previews can be flaky on the free tier), newest, full Flash before Lite. */
    private fun score(name: String): Double {
        if (!name.startsWith("gemini-") || !name.contains("flash")) return 0.0
        val skip = listOf("image", "tts", "audio", "live", "embedding", "robotics", "computer", "native", "thinking")
        if (skip.any { name.contains(it) }) return 0.0
        val version = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        var s = version * 10
        if (name.contains("lite")) s -= 3
        if (name.contains("preview") || name.contains("exp")) s -= 40
        if (Regex("-\\d{2}-\\d{2}|-\\d{3}$").containsMatchIn(name)) s -= 0.5 // dated snapshots
        return s
    }

    /** One model turn. Returns the model's "content" object (role + parts), unchanged. */
    fun generate(key: String, model: String, system: String, contents: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", tools)
            .put("generationConfig", JSONObject().put("temperature", 0.6).put("maxOutputTokens", 2048))
        val json = request("POST", "$BASE/models/$model:generateContent", key, body)
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw ApiError(blockReason(json), 400)
        return cand.optJSONObject("content")
            ?: JSONObject().put("role", "model").put("parts", JSONArray())
    }

    private fun blockReason(json: JSONObject): String {
        val r = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (r.isNotBlank()) "Gemini didn't answer ($r)" else "Gemini sent an empty answer"
    }

    /** Tries twice if the network blips (DNS / timeout), then gives a clear "no internet" message. */
    private fun request(method: String, url: String, key: String, body: JSONObject?): JSONObject {
        var last: java.io.IOException? = null
        repeat(2) { attempt ->
            try {
                return requestOnce(method, url, key, body)
            } catch (e: java.net.UnknownHostException) {
                last = e
            } catch (e: java.net.SocketTimeoutException) {
                last = e
            } catch (e: java.net.ConnectException) {
                last = e
            }
            if (attempt == 0) Thread.sleep(1500)
        }
        throw ApiError(
            if (last is java.net.SocketTimeoutException) "Google took too long to answer. Check your internet and try again."
            else "No internet connection: couldn't reach Google. Check mobile data or Wi-Fi for Nova.",
            -1
        )
    }

    private fun requestOnce(method: String, url: String, key: String, body: JSONObject?): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault("HTTP $code")
                throw ApiError(friendly(code, msg), code)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(code: Int, msg: String): String {
        val detail = msg.take(140)
        return when (code) {
            400 -> if (msg.contains("API key", true)) "That API key isn't valid. Check it in Settings." else "Gemini error: $detail"
            401, 403 -> "Your API key was refused. Check it in Settings."
            429 -> "Free limit reached for now. Try again in a minute. ($detail)"
            in 500..599 -> "Gemini is busy right now ($code). $detail"
            else -> "Gemini error $code: $detail"
        }
    }
}
