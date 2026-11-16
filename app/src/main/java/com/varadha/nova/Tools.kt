package com.varadha.nova

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.DrawableRes
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** What the screen shows for an action: a small card, optionally with a countdown and Cancel. */
data class ActionCard(
    @DrawableRes val icon: Int,
    val title: String,
    val subtitle: String = "",
    /** Seconds before [run] fires by itself (0 = already done / nothing to run). */
    val countdown: Int = 0,
    val run: (() -> Unit)? = null
)

/** The app screen, as seen by the tools. All calls are made on the main thread by the host. */
interface Host {
    fun launch(intent: Intent): Boolean
    fun card(card: ActionCard)
    fun askPermission(permission: String)
}

/**
 * Everything Nova can do on the phone. Each tool is described to Gemini (name, what it does,
 * its inputs); Gemini decides when to use them, and the result is sent back so it can reply.
 */
class Tools(private val c: Context, private val host: Host) {

    // ---------------------------------------------------------------- descriptions for Gemini

    val declarations: JSONArray by lazy {
        JSONArray().put(
            JSONObject().put(
                "functionDeclarations", JSONArray()
                    .put(fn("make_call", "Phone call to a contact (by name as saved, or relation like Amma) or a number.",
                        "contact" to str("Contact name or phone number")))
                    .put(fn("send_message", "Open a message to a contact with the text filled in, ready to send.",
                        "contact" to str("Contact name or phone number"),
                        "text" to str("Message text"),
                        "app" to enumStr("whatsapp or sms", "whatsapp", "sms")))
                    .put(fn("set_alarm", "Set an alarm in the clock app.",
                        "hour" to int("Hour 0-23"), "minute" to int("Minute 0-59"),
                        "label" to str("Optional label"), required = listOf("hour", "minute")))
                    .put(fn("set_timer", "Start a countdown timer.",
                        "seconds" to int("Length in seconds"), "label" to str("Optional label"),
                        required = listOf("seconds")))
                    .put(fn("set_reminder", "Remind the user with a notification at a date and time.",
                        "text" to str("What to remind about"),
                        "when" to str("Local date-time, format yyyy-MM-dd'T'HH:mm")))
                    .put(fn("open_app", "Open an installed app by name.",
                        "name" to str("App name, e.g. WhatsApp, Instagram, Camera")))
                    .put(fn("navigate", "Start Google Maps navigation to a place.",
                        "destination" to str("Place or address"),
                        "mode" to enumStr("Travel mode", "driving", "two_wheeler", "walking", "transit"),
                        required = listOf("destination")))
                    .put(fn("play_youtube", "Search and play on YouTube.", "query" to str("What to play")))
                    .put(fn("search_web", "Search Google for something the user wants to see.", "query" to str("Search text")))
                    .put(fn("open_camera", "Open the camera."))
                    .put(fn("flashlight", "Turn the torch on or off.", "on" to bool("true = on")))
                    .put(fn("ringer_mode", "Change sound mode.",
                        "mode" to enumStr("Sound mode", "normal", "vibrate", "silent")))
                    .put(fn("open_settings", "Open a settings screen or quick panel.",
                        "screen" to enumStr("Which one", "wifi", "internet", "bluetooth", "volume", "display", "battery", "location", "apps")))
                    .put(fn("upi_pay", "Open a UPI payment app with payee and amount filled in. The user confirms with their PIN.",
                        "upi_id" to str("Payee UPI ID, like name@okaxis"),
                        "name" to str("Payee name"),
                        "amount" to num("Amount in rupees"),
                        "note" to str("Optional note"),
                        required = listOf("upi_id", "amount")))
                    .put(fn("remember", "Save a lasting fact about the user for later (UPI IDs, who someone is, preferences).",
                        "fact" to str("The fact, as a short sentence")))
                    .put(fn("forget", "Delete remembered facts that contain some text.",
                        "text" to str("Text to match")))
            )
        )
    }

    // ---------------------------------------------------------------- running a tool

    /** Runs one tool. Called on a background thread. Returns the result for Gemini. */
    fun run(name: String, a: JSONObject): JSONObject = when (name) {
        "make_call" -> makeCall(a.optString("contact"))
        "send_message" -> sendMessage(a.optString("contact"), a.optString("text"), a.optString("app", "whatsapp"))
        "set_alarm" -> setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label"))
        "set_timer" -> setTimer(a.optInt("seconds"), a.optString("label"))
        "set_reminder" -> setReminder(a.optString("text"), a.optString("when"))
        "open_app" -> openApp(a.optString("name"))
        "navigate" -> navigate(a.optString("destination"), a.optString("mode", "driving"))
        "play_youtube" -> openUrl(R.drawable.ic_play, "YouTube: ${a.optString("query")}",
            "https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))
        "search_web" -> searchWeb(a.optString("query"))
        "open_camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), R.drawable.ic_camera, "Camera")
        "flashlight" -> flashlight(a.optBoolean("on", true))
        "ringer_mode" -> ringer(a.optString("mode"))
        "open_settings" -> openSettings(a.optString("screen"))
        "upi_pay" -> upiPay(a.optString("upi_id"), a.optString("name"), a.optDouble("amount", 0.0), a.optString("note"))
        "remember" -> {
            Prefs.remember(c, a.optString("fact"))
            host.card(ActionCard(R.drawable.ic_memory, "Remembered", a.optString("fact")))
            ok("saved")
        }
        "forget" -> {
            val n = Prefs.forget(c, a.optString("text"))
            ok(if (n > 0) "forgot $n fact(s)" else "nothing matched")
        }
        else -> err("unknown tool $name")
    }

    // ---------------------------------------------------------------- calls & messages

    private fun makeCall(who: String): JSONObject {
        val contact = resolve(who) ?: return contactError(who)
        val direct = granted(Manifest.permission.CALL_PHONE)
        if (!direct) host.askPermission(Manifest.permission.CALL_PHONE)
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))
        // A short countdown with Cancel, in case the name was misheard.
        host.card(ActionCard(R.drawable.ic_call, "Calling ${contact.name}", contact.number, countdown = 3) {
            host.launch(intent)
        })
        return ok("calling ${contact.name} in 3 seconds (user can cancel)")
    }

    private fun sendMessage(who: String, text: String, app: String): JSONObject {
        val contact = resolve(who) ?: return contactError(who)
        val intent = if (app == "sms") {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}")).putExtra("sms_body", text)
        } else {
            val digits = contact.number.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it.removePrefix("0") }
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))).apply {
                if (installed("com.whatsapp")) setPackage("com.whatsapp")
            }
        }
        if (!host.launch(intent)) return err("couldn't open ${if (app == "sms") "messages" else "WhatsApp"}")
        host.card(ActionCard(R.drawable.ic_message, "Message to ${contact.name}", "“$text” · tap Send"))
        return ok("message opened with text filled in; user taps Send")
    }

    private data class Contact(val name: String, val number: String)

    /** Finds a contact by name (best match) or accepts a number. */
    private fun resolve(who: String): Contact? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return Contact(who, digits)
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            host.askPermission(Manifest.permission.READ_CONTACTS)
            return null
        }
        val q = who.trim()
        val found = mutableListOf<Contact>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0)
                val num = cur.getString(1)
                if (n != null && num != null) found += Contact(n, num)
            }
        }
        return found.distinctBy { it.name.lowercase() }.minByOrNull { rank(it.name, q) }
    }

    private fun rank(name: String, q: String): Int = when {
        name.equals(q, true) -> 0
        name.startsWith(q, true) -> 1
        name.split(" ").any { it.startsWith(q, true) } -> 2
        else -> 3 + name.length
    }

    private fun contactError(who: String) = if (!granted(Manifest.permission.READ_CONTACTS))
        err("Contacts permission was just requested. Ask the user to allow it and say the command again.")
    else err("No contact named \"$who\". Ask the user for the exact saved name or the number.")

    // ---------------------------------------------------------------- time

    private fun setAlarm(hour: Int, minute: Int, label: String): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
        if (!host.launch(intent)) return err("no clock app found")
        host.card(ActionCard(R.drawable.ic_alarm, "Alarm set", "${clock(hour, minute)}${if (label.isNotBlank()) " · $label" else ""}"))
        return ok("alarm set for ${clock(hour, minute)}")
    }

    private fun setTimer(seconds: Int, label: String): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }
        if (!host.launch(intent)) return err("no clock app found")
        host.card(ActionCard(R.drawable.ic_timer, "Timer started", duration(seconds)))
        return ok("timer started for ${duration(seconds)}")
    }

    private fun setReminder(text: String, whenText: String): JSONObject {
        val time = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(whenText)!!.time
        }.getOrNull() ?: return err("couldn't read the time \"$whenText\"")
        if (time < System.currentTimeMillis()) return err("that time has already passed")
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            host.askPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
        Reminders.add(c, text, time)
        val pretty = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(time)
        host.card(ActionCard(R.drawable.ic_reminder, "Reminder: $text", pretty))
        return ok("reminder set for $pretty")
    }

    // ---------------------------------------------------------------- apps & places

    private fun openApp(name: String): JSONObject {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.map { it to it.loadLabel(pm).toString() }
            .filter { (_, label) -> label.contains(name, true) || name.contains(label, true) }
            .minByOrNull { (_, label) -> rank(label, name) }
            ?: return err("no app called \"$name\" is installed")
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName) ?: return err("can't open ${best.second}")
        host.launch(intent)
        host.card(ActionCard(R.drawable.ic_apps, "Opening ${best.second}"))
        return ok("opened ${best.second}")
    }

    private fun navigate(destination: String, mode: String): JSONObject {
        val m = when (mode) { "two_wheeler" -> "l"; "walking" -> "w"; "transit" -> "r"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$m"))
        val ok = host.launch(nav) || host.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))
        if (!ok) return err("no maps app found")
        host.card(ActionCard(R.drawable.ic_navigation, "Directions", destination))
        return ok("navigation started")
    }

    private fun searchWeb(query: String): JSONObject {
        val ok = host.launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            host.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        if (!ok) return err("no browser found")
        host.card(ActionCard(R.drawable.ic_search, "Searching", query))
        return ok("search opened")
    }

    private fun openUrl(@DrawableRes icon: Int, title: String, url: String): JSONObject {
        if (!host.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return err("couldn't open")
        host.card(ActionCard(icon, title))
        return ok("opened")
    }

    private fun launch(intent: Intent, @DrawableRes icon: Int, title: String): JSONObject {
        if (!host.launch(intent)) return err("couldn't open $title")
        host.card(ActionCard(icon, title))
        return ok("opened")
    }

    // ---------------------------------------------------------------- phone settings

    private fun flashlight(on: Boolean): JSONObject {
        val cm = c.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return err("this phone has no flashlight")
        cm.setTorchMode(id, on)
        host.card(ActionCard(R.drawable.ic_flashlight, if (on) "Torch on" else "Torch off"))
        return ok(if (on) "torch on" else "torch off")
    }

    private fun ringer(mode: String): JSONObject {
        val am = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        val target = when (mode) {
            "silent" -> AudioManager.RINGER_MODE_SILENT
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        if (target == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
            host.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return err("needs Do Not Disturb access; settings opened, ask the user to allow Nova and try again")
        }
        return runCatching {
            am.ringerMode = target
            host.card(ActionCard(R.drawable.ic_volume, "Sound: ${mode.replaceFirstChar { it.uppercase() }}"))
            ok("ringer is now $mode")
        }.getOrElse { err("couldn't change sound mode") }
    }

    private fun openSettings(screen: String): JSONObject {
        val intent = when (screen) {
            "wifi" -> Intent(Settings.Panel.ACTION_WIFI)
            "internet" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            "volume" -> Intent(Settings.Panel.ACTION_VOLUME)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        if (!host.launch(intent) && !host.launch(Intent(Settings.ACTION_SETTINGS))) return err("couldn't open settings")
        host.card(ActionCard(R.drawable.ic_settings, "Settings: $screen"))
        return ok("opened")
    }

    // ---------------------------------------------------------------- money

    private fun upiPay(upi: String, name: String, amount: Double, note: String): JSONObject {
        if (!upi.contains("@")) return err("need a valid UPI ID like name@okaxis")
        if (amount <= 0) return err("need an amount")
        val amt = String.format(Locale.US, "%.2f", amount)
        val uri = Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", upi)
            .appendQueryParameter("pn", name.ifBlank { upi })
            .appendQueryParameter("am", amt)
            .appendQueryParameter("cu", "INR")
            .apply { if (note.isNotBlank()) appendQueryParameter("tn", note) }
            .build()
        val pay = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Pay ₹$amt with")
        if (!host.launch(pay)) return err("no UPI app found")
        host.card(ActionCard(R.drawable.ic_rupee, "Pay ₹$amt to ${name.ifBlank { upi }}", "$upi · confirm with your PIN"))
        return ok("payment app opened; user confirms with PIN")
    }

    // ---------------------------------------------------------------- helpers

    private fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun installed(pkg: String) =
        runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun clock(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
    }

    private fun duration(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return listOfNotNull(
            if (h > 0) "$h hr" else null,
            if (m > 0) "$m min" else null,
            if (sec > 0 && h == 0) "$sec sec" else null
        ).joinToString(" ").ifBlank { "0 sec" }
    }

    private fun ok(msg: String) = JSONObject().put("result", msg)
    private fun err(msg: String) = JSONObject().put("error", msg)

    // JSON-schema helpers for the declarations
    private fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
    private fun int(d: String) = JSONObject().put("type", "INTEGER").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "NUMBER").put("description", d)
    private fun bool(d: String) = JSONObject().put("type", "BOOLEAN").put("description", d)
    private fun enumStr(d: String, vararg values: String) =
        str(d).put("enum", JSONArray().apply { values.forEach { put(it) } })

    private fun fn(
        name: String,
        description: String,
        vararg props: Pair<String, JSONObject>,
        required: List<String>? = null
    ): JSONObject {
        val o = JSONObject().put("name", name).put("description", description)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, v) }
            o.put(
                "parameters", JSONObject().put("type", "OBJECT").put("properties", p)
                    .put("required", JSONArray().apply { (required ?: props.map { it.first }).forEach { put(it) } })
            )
        }
        return o
    }
}
