package com.varadha.nova

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import com.google.android.material.snackbar.Snackbar
import com.varadha.nova.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** The conversation screen: mic, chat, and the things Nova does. */
class MainActivity : AppCompatActivity(), Host, Listener.Events {

    private lateinit var b: ActivityMainBinding
    private lateinit var listener: Listener
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private val worker = Executors.newSingleThreadExecutor()
    private val timers = mutableMapOf<Msg.Action, CountDownTimer>()
    private val chat = ChatAdapter(onCancel = ::cancelAction, onNow = ::runActionNow)
    private var thinking = false
    private var lastWasVoice = false
    private var listenWhenAllowed = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && listenWhenAllowed) startListening()
        else if (!ok) snack("Nova needs the microphone to hear you")
        listenWhenAllowed = false
    }

    private val askOther = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) snack("Allowed. Say that again.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        listener = Listener(this, this)
        speaker = Speaker(this) { runOnUiThread { if (!thinking && !listener.active) setIdle() } }
        brain = Brain(applicationContext, Tools(applicationContext, this))
        Reminders.ensureChannel(this)

        b.chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.chat.adapter = chat

        b.mic.setOnClickListener {
            if (listener.active) {
                listener.stop()
                resetMic()
                setIdle()
            } else startListening()
        }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnAddKey.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnNewChat.setOnClickListener { newConversation() }
        b.btnPowerSetup.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnSend.setOnClickListener { sendTyped() }
        b.listenOverlay.setOnClickListener {
            listener.stop()
            resetMic()
            setIdle()
        }
        b.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) sendTyped().let { true } else false
        }

        buildSuggestions()

        Updater.check(this) { b.status.text = "Update ${it.versionText} available · tap" ; b.status.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) } }

        handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /** Opened from the widget, tile, shortcut or assist gesture: start listening right away. */
    private fun handleLaunch(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_FROM_ASSIST, false) == true || i?.action == Intent.ACTION_ASSIST) {
            // Opened with the power button: show over the lock screen and wake the display.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (i?.action == ACTION_LISTEN || i?.action == Intent.ACTION_ASSIST || i?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            b.root.post { startListening() }
        }
    }

    override fun onResume() {
        super.onResume()
        val name = Prefs.userName(this)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        b.hello.text = if (name.isBlank()) part else "$part,\n$name"
        b.hello.post { gradientText(b.hello) }
        b.title.text = Prefs.assistantName(this)
        b.keyCard.visibility = if (Prefs.apiKey(this).isBlank()) View.VISIBLE else View.GONE
        b.powerCard.visibility =
            if (Prefs.apiKey(this).isNotBlank() && !Assistant.isDefault(this)) View.VISIBLE else View.GONE
        updateWelcome()
    }

    override fun onPause() {
        listener.stop()
        speaker.stop()
        super.onPause()
    }

    override fun onDestroy() {
        timers.values.forEach { it.cancel() }
        speaker.shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- talking

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listenWhenAllowed = true
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!listener.available) {
            snack("Speech recognition isn't available. Install or update the Google app.")
            return
        }
        speaker.stop()
        b.listenText.text = "Listening…"
        b.listenOverlay.alpha = 0f
        b.listenOverlay.visibility = View.VISIBLE
        b.listenOverlay.animate().alpha(1f).setDuration(180).start()
        b.orbListen.active = true
        setStatus("Listening…", active = true)
        b.micIcon.setImageResource(R.drawable.ic_stop)
        listener.start(Prefs.listenLang(this))
    }

    override fun onListening() = Unit

    override fun onLevel(level: Float) {
        b.orbSmall.setLevel(level)
        b.orbBig.setLevel(level)
        b.orbListen.setLevel(level)
        b.pulse.alpha = 0.15f + 0.35f * level
        val s = 1f + 0.25f * level
        b.pulse.scaleX = s
        b.pulse.scaleY = s
    }

    override fun onPartial(text: String) {
        b.listenText.text = text
    }

    override fun onFinal(text: String) {
        resetMic()
        send(text, voice = true)
    }

    override fun onListenError(message: String) {
        resetMic()
        setIdle()
        snack(message)
    }

    private fun resetMic() {
        b.listenOverlay.visibility = View.GONE
        b.orbListen.active = false
        b.micIcon.setImageResource(R.drawable.ic_mic)
        b.pulse.alpha = 0f
    }

    /** "Try asking" cards, two per row. */
    private fun buildSuggestions() {
        val items = listOf(
            R.drawable.ic_alarm to "Wake me up at 6:30",
            R.drawable.ic_reminder to "Remind me in 10 minutes to drink water",
            R.drawable.ic_call to "Amma ku call pannu",
            R.drawable.ic_flashlight to "Turn on the torch",
            R.drawable.ic_play to "Play Ilaiyaraaja songs",
            R.drawable.ic_navigation to "Navigate to the nearest petrol bunk",
            R.drawable.ic_memory to "Explain normalisation simply",
            R.drawable.ic_rupee to "What's 18% GST on 4500?"
        )
        val tints = intArrayOf(0xFFB39DFF.toInt(), 0xFFF59BD3.toInt(), 0xFF7DD8FF.toInt(), 0xFFFFC27A.toInt())
        b.suggestions.removeAllViews()
        items.chunked(2).forEachIndexed { row, pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { col, (icon, text) ->
                val card = LayoutInflater.from(this).inflate(R.layout.item_suggestion, line, false)
                card.findViewById<ImageView>(R.id.icon).apply {
                    setImageResource(icon)
                    setColorFilter(tints[(row * 2 + col) % tints.size])
                }
                card.findViewById<TextView>(R.id.label).text = text
                card.setOnClickListener { send(text, voice = false) }
                line.addView(card)
            }
            b.suggestions.addView(line)
        }
    }

    /** Violet → pink gradient on the greeting. */
    private fun gradientText(t: TextView) {
        val w = t.paint.measureText(t.text.toString().lines().maxByOrNull { it.length }.orEmpty())
        if (w <= 0f) return
        val start = (t.width - w) / 2f
        t.paint.shader = LinearGradient(start, 0f, start + w, 0f,
            intArrayOf(0xFFB39DFF.toInt(), 0xFFF59BD3.toInt(), 0xFF7DD8FF.toInt()), null, Shader.TileMode.CLAMP)
        t.invalidate()
    }

    private fun sendTyped() {
        val text = b.input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        b.input.setText("")
        send(text, voice = false)
    }

    /** Sends a request to the brain (in the background) and shows the reply. */
    fun send(text: String, voice: Boolean) {
        if (thinking) return
        thinking = true
        lastWasVoice = voice
        chat.add(Msg.User(text))
        chat.add(Msg.Typing)
        scrollDown()
        updateWelcome()
        setStatus("Thinking…", active = true)
        worker.execute {
            val reply = runCatching { brain.ask(text) }.getOrElse { it.message ?: "Something went wrong." }
            runOnUiThread { showReply(reply) }
        }
    }

    /** Also used by the screenshot test to fill the conversation. */
    fun showReply(reply: String) {
        thinking = false
        chat.removeTyping()
        chat.add(Msg.Nova(reply))
        scrollDown()
        updateWelcome()
        if (lastWasVoice && Prefs.voiceReplies(this)) {
            setStatus("Speaking…", active = true)
            speaker.say(reply)
        } else setIdle()
    }

    /** Screenshot test only: shows the listening view with some words. */
    fun demoListening(words: String) {
        b.listenText.text = words
        b.listenOverlay.visibility = View.VISIBLE
        b.orbListen.active = true
        b.orbListen.setLevel(0.7f)
    }

    /** Screenshot test only: shows a sample conversation without calling Gemini. */
    fun demo() {
        chat.add(Msg.User("Wake me up at 6:30 tomorrow"))
        chat.add(Msg.Action(ActionCard(R.drawable.ic_alarm, "Alarm set", "6:30 AM")))
        chat.add(Msg.Nova("Done! Your alarm is set for 6:30 AM tomorrow."))
        chat.add(Msg.User("Amma ku call pannu"))
        val call = Msg.Action(ActionCard(R.drawable.ic_call, "Calling Amma", "+91 98765 43210", countdown = 3))
        call.progress = 450
        chat.add(call)
        chat.add(Msg.Nova("Amma ku call panren."))
        chat.add(Msg.User("What's 18% GST on 4500?"))
        chat.add(Msg.Nova("18% GST on ₹4,500 is ₹810, so the total is ₹5,310."))
        updateWelcome()
        scrollDown()
    }

    private fun newConversation() {
        speaker.stop()
        listener.stop()
        timers.values.forEach { it.cancel() }
        timers.clear()
        brain.reset()
        chat.clear()
        thinking = false
        resetMic()
        setIdle()
        updateWelcome()
    }

    private fun updateWelcome() {
        val empty = chat.items.isEmpty()
        b.welcome.visibility = if (empty) View.VISIBLE else View.GONE
        b.chat.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun scrollDown() = b.chat.post { b.chat.scrollToPosition(chat.itemCount - 1) }

    private fun setStatus(text: String, active: Boolean) {
        b.status.text = text
        b.orbSmall.active = active
        b.orbBig.active = active
    }

    private fun setIdle() = setStatus("Ready", active = false)

    // ---------------------------------------------------------------- Host (used by Tools)

    override fun launch(intent: Intent): Boolean = onMain {
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km.isKeyguardLocked) {
                // Opened from the lock screen: ask to unlock, then do it.
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        runCatching { startActivity(intent) }
                    }
                })
                return@onMain true
            }
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    override fun card(card: ActionCard) {
        runOnUiThread {
            val m = Msg.Action(card)
            // Show the card just above the "Thinking…" row.
            chat.removeTyping()
            chat.add(m)
            if (thinking) chat.add(Msg.Typing)
            scrollDown()
            if (card.countdown > 0) startCountdown(m)
        }
    }

    override fun askPermission(permission: String) {
        runOnUiThread { askOther.launch(permission) }
    }

    private fun startCountdown(m: Msg.Action) {
        val total = m.card.countdown * 1000L
        timers[m] = object : CountDownTimer(total, 50) {
            override fun onTick(left: Long) {
                m.progress = (1000 * (total - left) / total).toInt()
                chat.changed(m)
            }

            override fun onFinish() = runActionNow(m)
        }.start()
    }

    private fun runActionNow(m: Msg.Action) {
        if (m.state != Msg.Action.State.WAITING) return
        timers.remove(m)?.cancel()
        speaker.stop()
        m.state = Msg.Action.State.DONE
        m.progress = 1000
        chat.changed(m)
        m.card.run?.invoke()
    }

    private fun cancelAction(m: Msg.Action) {
        if (m.state != Msg.Action.State.WAITING) return
        timers.remove(m)?.cancel()
        m.state = Msg.Action.State.CANCELLED
        chat.changed(m)
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        runOnUiThread(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun snack(text: String) =
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(b.inputRow).show()

    companion object {
        const val ACTION_LISTEN = "com.varadha.nova.LISTEN"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_FROM_ASSIST = "from_assist"
    }
}
