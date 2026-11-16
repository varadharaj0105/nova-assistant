package com.varadha.nova

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/** "Remind me at 8 to submit the form": a notification at that time, kept across restarts. */
object Reminders {
    private const val KEY = "reminders"
    const val CHANNEL = "reminders"

    data class Reminder(val id: Int, val text: String, val time: Long)

    fun all(c: Context): List<Reminder> = runCatching {
        val a = JSONArray(Prefs.prefs(c).getString(KEY, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Reminder(o.getInt("id"), o.getString("text"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time)) }
        Prefs.prefs(c).edit().putString(KEY, a.toString()).apply()
    }

    fun add(c: Context, text: String, time: Long) {
        val r = Reminder((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), text, time)
        save(c, all(c) + r)
        schedule(c, r)
    }

    fun remove(c: Context, id: Int) {
        save(c, all(c).filterNot { it.id == id })
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id, ""))
    }

    /** After a restart, alarms are gone: set the future ones again. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        val (future, past) = all(c).partition { it.time > now }
        save(c, future)
        future.forEach { schedule(c, it) }
        past.size // past ones are dropped
    }

    private fun schedule(c: Context, r: Reminder) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = pending(c, r.id, r.text)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi)
        }
    }

    private fun pending(c: Context, id: Int, text: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Reminders you asked Nova to set" }
            )
        }
    }
}

/** Shows the reminder notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text").orEmpty()
        Reminders.ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, id, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
        Reminders.remove(c, id)
    }
}

/** Sets reminders again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(c)
    }
}
