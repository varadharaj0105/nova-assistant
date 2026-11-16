package com.varadha.nova

import android.Manifest
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
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
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
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        listener = Listener(this, this)
        speaker = Speaker(this) { runOnUiThread { if (!thinking && !listener.active) setIdle() } }
        brain = Brain(applicationContext, Tools(applicationContext, this))
        Reminders.ensureChannel(this)

        b.chat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.chat.adapter = chat

        b.mic.setOnClickListener { if (listener.active) listener.stop().also { setIdle() } else startListening() }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnAddKey.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnNewChat.setOnClickListener { newConversation() }
        b.inputLayout.setEndIconOnClickListener { sendTyped() }
        b.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) sendTyped().let { true } else false
        }

        listOf(
            "Set an alarm for 6:30 AM",
            "Remind me in 10 minutes to drink water",
            "Call Amma",
            "Turn on the torch",
            "Play Ilaiyaraaja songs on YouTube",
            "Navigate to the nearest petrol bunk",
            "What's 18% GST on 4500?",
            "Remember my UPI is varadha@okaxis"
        ).forEach { s ->
            b.suggestions.addView(Chip(this).apply {
                text = s
                setOnClickListener { send(s, voice = false) }
            })
        }

        Updater.check(this) { b.status.text = "Update ${it.versionText} available · tap" ; b.status.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) } }

        handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /** Opened from the widget, tile, shortcut or assist gesture: start listening right away. */
    private fun handleLaunch(i: Intent?) {
        if (i?.action == ACTION_LISTEN || i?.action == Intent.ACTION_ASSIST || i?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            b.root.post { startListening() }
        }
    }

    override fun onResume() {
        super.onResume()
        val name = Prefs.userName(this)
        b.hello.text = if (name.isBlank()) "Hi there" else "Hi $name"
        b.title.text = Prefs.assistantName(this)
        b.keyCard.visibility = if (Prefs.apiKey(this).isBlank()) View.VISIBLE else View.GONE
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
        b.partial.text = "Listening…"
        b.partial.visibility = View.VISIBLE
        setStatus("Listening…", active = true)
        b.mic.setImageResource(R.drawable.ic_stop)
        listener.start(Prefs.listenLang(this))
    }

    override fun onListening() = Unit

    override fun onLevel(level: Float) {
        b.orbSmall.setLevel(level)
        b.orbBig.setLevel(level)
        b.pulse.alpha = 0.15f + 0.35f * level
        val s = 1f + 0.25f * level
        b.pulse.scaleX = s
        b.pulse.scaleY = s
    }

    override fun onPartial(text: String) {
        b.partial.text = text
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
        b.partial.visibility = View.GONE
        b.mic.setImageResource(R.drawable.ic_mic)
        b.pulse.alpha = 0f
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
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(b.mic).show()

    companion object {
        const val ACTION_LISTEN = "com.varadha.nova.LISTEN"
        const val EXTRA_LISTEN = "listen"
    }
}
