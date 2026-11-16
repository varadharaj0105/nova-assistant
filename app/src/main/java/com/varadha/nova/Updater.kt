package com.varadha.nova

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer build, downloads it inside the app and installs it over itself.
 * Runs only while the app screen is open, never in the background.
 */
object Updater {
    private const val REPO = "varadharaj0105/nova-assistant"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/Nova.apk"

    data class Release(val build: Int, val notes: String) {
        val versionText get() = "1.$build"
    }

    private val main = Handler(Looper.getMainLooper())

    /** The screen listens here for install results (e.g. "failed"). */
    var onInstallResult: ((ok: Boolean, message: String?) -> Unit)? = null

    /** Calls [onNewer] on the main thread only if GitHub has a newer build. */
    fun check(context: Context, onNewer: (Release) -> Unit) {
        val current = currentBuild(context)
        Thread {
            val latest = runCatching { fetchLatest() }.getOrNull() ?: return@Thread
            if (latest.build > current) main.post { onNewer(latest) }
        }.start()
    }

    fun currentBuild(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }

    /** Release tags look like "build-7"; the release text is the change description. */
    private fun fetchLatest(): Release? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val notes = json.optString("body").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return Release(build, notes)
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- download + install ----------------

    private fun apkFile(c: Context) = File(c.cacheDir, "update.apk")

    /** Removes a leftover download after a successful update. */
    fun cleanUp(c: Context) {
        apkFile(c).delete()
    }

    /** Can this app install updates? (Android's "Install unknown apps" switch for this app.) */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /**
     * Downloads the latest APK into the app's cache, reporting progress 0..100 on the main thread,
     * then hands it to Android's installer.
     */
    fun downloadAndInstall(
        c: Context,
        onProgress: (Int) -> Unit,
        onInstalling: () -> Unit,
        onError: (String) -> Unit
    ) {
        val app = c.applicationContext
        Thread {
            try {
                val file = apkFile(app)
                download(file) { p -> main.post { onProgress(p) } }
                main.post { onInstalling() }
                install(app, file)
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Download failed") }
            }
        }.start()
    }

    private fun download(file: File, onProgress: (Int) -> Unit) {
        var url = URL(DOWNLOAD_URL)
        var conn: HttpURLConnection
        // Follow GitHub's redirect to the file host by hand (works on every Android version).
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                hops++
                continue
            }
            if (code != 200) throw IllegalStateException("Server answered $code")
            break
        }
        try {
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Installs the APK over this app. On Android 12+ later updates can go through without a
     * confirmation screen once this app installed itself the first time.
     */
    private fun install(c: Context, file: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun reportResult(ok: Boolean, message: String?) {
        main.post { onInstallResult?.invoke(ok, message) }
    }
}
