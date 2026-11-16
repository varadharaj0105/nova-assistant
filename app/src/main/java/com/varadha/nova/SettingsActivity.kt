package com.varadha.nova

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.varadha.nova.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private var pendingUpdateAfterPermission = false
    private var updating = false

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        // Brain
        b.keyInput.setText(Prefs.apiKey(this))
        renderKeyStatus()
        b.btnGetKey.setOnClickListener {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        b.btnSaveKey.setOnClickListener { saveAndTestKey() }
        b.btnModel.setOnClickListener { chooseModel() }

        // Personal
        b.userName.setText(Prefs.userName(this))
        b.assistantName.setText(Prefs.assistantName(this))

        // Voice
        b.switchVoice.isChecked = Prefs.voiceReplies(this)
        b.switchVoice.setOnCheckedChangeListener { _, on -> Prefs.setVoiceReplies(this, on) }
        b.langGroup.check(if (Prefs.listenLang(this) == "ta-IN") R.id.langTamil else R.id.langEnglish)
        b.langGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) Prefs.setListenLang(this, if (id == R.id.langTamil) "ta-IN" else "en-IN")
        }

        // Quick access
        b.btnAssistant.setOnClickListener { Assistant.openSettings(this) }
        b.btnWidget.setOnClickListener { addWidget() }
        b.btnTile.setOnClickListener { addTile() }

        // About
        b.version.text = "Version 1.${Updater.currentBuild(this)}"
        b.btnUpdate.setOnClickListener { checkOrUpdate() }
        Updater.cleanUp(this)
        Updater.onInstallResult = { ok, msg ->
            updating = false
            if (!ok) {
                b.updateProgress.visibility = View.GONE
                b.btnUpdate.isEnabled = true
                b.btnUpdate.text = "Update"
                b.updateText.text = msg ?: "Update failed"
            }
        }
        Updater.check(this) { showUpdate(it) }
    }

    override fun onResume() {
        super.onResume()
        renderMemory()
        renderPermissions()
        renderAssistant()
        renderKeyStatus()
        if (pendingUpdateAfterPermission && Updater.canInstall(this)) {
            pendingUpdateAfterPermission = false
            startUpdate()
        }
    }

    override fun onPause() {
        Prefs.setUserName(this, b.userName.text?.toString().orEmpty())
        Prefs.setAssistantName(this, b.assistantName.text?.toString().orEmpty())
        super.onPause()
    }

    override fun onDestroy() {
        Updater.onInstallResult = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- key

    private fun renderKeyStatus() {
        val model = Prefs.model(this)
        val status = when {
            Prefs.apiKey(this).isBlank() -> "Get a free key from Google AI Studio, paste it here, then tap Save & test."
            model != null -> "Connected · using $model"
            else -> "Saved · not tested yet"
        }
        val err = Prefs.lastError(this)
        b.keyStatus.text = if (err != null && Prefs.apiKey(this).isNotBlank()) "$status\nLast problem: $err" else status
    }

    /** Lets you pick which free Gemini model Nova uses. */
    private fun chooseModel() {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) return snack("Add your key first")
        b.keyStatus.text = "Loading models…"
        Thread {
            val list = runCatching { Gemini.models(key) }
            runOnUiThread {
                renderKeyStatus()
                list.onSuccess { models ->
                    if (models.isEmpty()) return@onSuccess snack("No models available for this key")
                    val current = models.indexOf(Prefs.model(this)).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Gemini model")
                        .setSingleChoiceItems(models.toTypedArray(), current) { d, which ->
                            Prefs.setModel(this, models[which])
                            Prefs.setLastError(this, null)
                            renderKeyStatus()
                            d.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }.onFailure { snack(it.message ?: "Couldn't load models") }
            }
        }.start()
    }

    private fun saveAndTestKey() {
        val key = b.keyInput.text?.toString().orEmpty().trim()
        if (key.isEmpty()) {
            b.keyLayout.error = "Paste your key first"
            return
        }
        b.keyLayout.error = null
        Prefs.setApiKey(this, key)
        b.keyStatus.text = "Testing…"
        b.btnSaveKey.isEnabled = false
        Thread {
            val result = runCatching { Gemini.pickModel(key) }
            runOnUiThread {
                b.btnSaveKey.isEnabled = true
                result.onSuccess {
                    Prefs.setModel(this, it)
                    renderKeyStatus()
                    snack("Connected! Nova is ready.")
                }.onFailure {
                    b.keyStatus.text = it.message ?: "Couldn't connect"
                    b.keyLayout.error = "Not working"
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- memory

    private fun renderMemory() {
        val facts = Prefs.memory(this)
        b.memoryEmpty.visibility = if (facts.isEmpty()) View.VISIBLE else View.GONE
        b.memoryList.removeAllViews()
        facts.forEach { fact ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_memory, b.memoryList, false)
            row.findViewById<TextView>(R.id.fact).text = fact
            row.findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
                Prefs.setMemory(this, Prefs.memory(this) - fact)
                renderMemory()
            }
            b.memoryList.addView(row)
        }
    }

    // ---------------------------------------------------------------- permissions

    private data class Perm(val permission: String, val title: String, val why: String)

    private fun renderPermissions() {
        val perms = buildList {
            add(Perm(Manifest.permission.RECORD_AUDIO, "Microphone", "To hear you"))
            add(Perm(Manifest.permission.READ_CONTACTS, "Contacts", "To call and message people by name"))
            add(Perm(Manifest.permission.CALL_PHONE, "Phone calls", "To call directly instead of opening the dialer"))
            if (Build.VERSION.SDK_INT >= 33) add(Perm(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "To show your reminders"))
        }
        b.permList.removeAllViews()
        perms.forEach { p ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_permission, b.permList, false)
            val ok = checkSelfPermission(p.permission) == PackageManager.PERMISSION_GRANTED
            row.findViewById<TextView>(R.id.permTitle).text = p.title
            row.findViewById<TextView>(R.id.permWhy).text = p.why
            val btn = row.findViewById<MaterialButton>(R.id.permButton)
            btn.text = if (ok) "Allowed" else "Allow"
            btn.isEnabled = !ok
            btn.setOnClickListener {
                if (shouldShowRequestPermissionRationale(p.permission) || !ok) ask.launch(p.permission)
            }
            b.permList.addView(row)
        }
        // Silent mode needs a special switch
        val row = LayoutInflater.from(this).inflate(R.layout.row_permission, b.permList, false)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val dnd = nm.isNotificationPolicyAccessGranted
        row.findViewById<TextView>(R.id.permTitle).text = "Do Not Disturb"
        row.findViewById<TextView>(R.id.permWhy).text = "To switch your phone to silent"
        row.findViewById<MaterialButton>(R.id.permButton).apply {
            text = if (dnd) "Allowed" else "Allow"
            isEnabled = !dnd
            setOnClickListener { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        }
        b.permList.addView(row)
    }

    // ---------------------------------------------------------------- quick access

    private fun renderAssistant() {
        val on = Assistant.isDefault(this)
        b.assistStatus.text = if (on) "Nova is your phone's assistant ✓" else "Not set up yet"
        b.btnAssistant.text = if (on) "Change assistant settings" else "Make Nova my assistant"
    }

    private fun addWidget() {
        val awm = getSystemService(AppWidgetManager::class.java)
        if (awm.isRequestPinAppWidgetSupported) {
            awm.requestPinAppWidget(ComponentName(this, NovaWidget::class.java), null, null)
        } else {
            info("Add the widget", "Long-press an empty spot on your home screen → Widgets → Nova.")
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, NovaTileService::class.java), "Nova",
                Icon.createWithResource(this, R.drawable.ic_mic), mainExecutor
            ) { if (it == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) snack("Tile added") }
        } else {
            info("Add the tile", "Pull down the notification panel, tap the pencil (edit) button, and drag “Nova” into your tiles.")
        }
    }

    // ---------------------------------------------------------------- updates

    private var available: Updater.Release? = null

    private fun showUpdate(r: Updater.Release) {
        available = r
        b.btnUpdate.text = "Update to ${r.versionText}"
        b.updateText.text = r.notes.ifBlank { "A new version is ready" }
        b.updateText.visibility = View.VISIBLE
    }

    private fun checkOrUpdate() {
        if (available != null) return startUpdate()
        b.updateText.text = "Checking…"
        b.updateText.visibility = View.VISIBLE
        var found = false
        Updater.check(this) {
            found = true
            showUpdate(it)
        }
        b.root.postDelayed({ if (!found) b.updateText.text = "You have the latest version" }, 4000)
    }

    private fun startUpdate() {
        if (updating) return
        if (!Updater.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow updates")
                .setMessage("To update itself, Nova needs permission to install apps.\n\nOn the next screen, turn on “Allow from this source”, then come back.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ ->
                    pendingUpdateAfterPermission = true
                    open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        updating = true
        b.btnUpdate.isEnabled = false
        b.updateProgress.visibility = View.VISIBLE
        b.updateText.text = "Downloading…"
        Updater.downloadAndInstall(
            this,
            onProgress = {
                b.updateProgress.setProgressCompat(it, true)
                b.btnUpdate.text = "$it%"
            },
            onInstalling = { b.updateText.text = "Installing… Nova will restart" },
            onError = {
                updating = false
                b.btnUpdate.isEnabled = true
                b.btnUpdate.text = "Try again"
                b.updateProgress.visibility = View.GONE
                b.updateText.text = "Couldn't download: $it"
            }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun open(i: Intent) = runCatching { startActivity(i) }.onFailure { snack("Couldn't open that") }

    private fun info(title: String, msg: String) =
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun snack(t: String) = Snackbar.make(b.root, t, Snackbar.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun LinearLayout.clear() = removeAllViews()
}
