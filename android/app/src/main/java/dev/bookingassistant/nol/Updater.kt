package dev.bookingassistant.nol

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 자동 업데이트 (ChartDesk의 Updater와 같은 방식).
 *
 * GitHub의 최신 릴리스에 더 높은 버전의 APK가 있으면 내려받아 두고 설치할지 묻는다.
 * 입력 화면을 열 때, 그리고 6시간이 지나 입력 화면으로 돌아올 때 확인한다. "업데이트 확인"으로도 확인한다.
 * 예매 화면에 있는 동안에는 묻지 않고, 입력 화면으로 돌아오면 묻는다.
 * 같은 서명 키로 만든 APK만 설치되므로, 다른 곳에서 받은 파일로 바뀔 걱정은 없다.
 */
class Updater(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        const val ACTION_INSTALL_STATUS = "dev.bookingassistant.nol.INSTALL_STATUS"
        const val REPO = "Sunya0804/booking-assistant"
        // 늘 최신 릴리스 하나만 본다. 중간 버전을 거치지 않고 곧바로 최신 버전을 설치한다.
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        private const val RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=50"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val USER_AGENT = "NOL-Booking-Assistant-Android"

        /** a가 b보다 높은 버전인가. 1.2.10 > 1.2.9 */
        fun newer(a: String, b: String): Boolean {
            val x = a.split(".")
            val y = b.split(".")
            for (i in 0 until maxOf(x.size, y.size)) {
                val left = x.getOrNull(i)?.let(::part) ?: 0
                val right = y.getOrNull(i)?.let(::part) ?: 0
                if (left != right) return left > right
            }
            return false
        }

        private fun part(s: String) = s.replace(Regex("\\D.*$"), "").toIntOrNull() ?: 0
    }

    private val current = BuildConfig.VERSION_NAME
    private val folder = File(activity.cacheDir, "updates")
    private var lastCheck = 0L
    private var busy = false
    // 내려받아 둔 새 버전. 설치를 미뤘으면 다음 확인 때 다시 묻는다.
    private var ready: File? = null
    private var readyVersion = ""
    private var readyNotes = ""
    private var askWhenResumed = false
    private var resumed = false
    // 설치 권한을 주러 설정에 다녀오는 중.
    private var waitingForPermission = false

    var status = "NOL 예매도우미 $current"
        private set(value) {
            field = value
            onStatus(value)
        }

    init {
        // 이미 설치된 버전 이하의 파일은 지운다.
        folder.listFiles()?.forEach {
            val version = it.name.removePrefix("update-").replace(Regex("\\.apk(\\.part)?$"), "")
            if (!newer(version, current)) it.delete()
        }
    }

    fun onResume() {
        resumed = true
        if (waitingForPermission && activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            install()
            return
        }
        if (askWhenResumed) ask()
        else if (System.currentTimeMillis() - lastCheck >= CHECK_EVERY_MS) check()
    }

    fun onPause() {
        resumed = false
    }

    /** 지금 확인한다. */
    fun check() {
        if (busy) return
        busy = true
        lastCheck = System.currentTimeMillis()
        scope.launch {
            try {
                run()
            } catch (e: Exception) {
                status = "업데이트 확인 실패: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun run() {
        status = "업데이트 확인 중…"
        val release = JSONObject(withContext(Dispatchers.IO) { fetch(LATEST) })
        val version = release.getString("tag_name").removePrefix("v")
        if (!newer(version, current)) {
            status = "최신 버전입니다 ($current)"
            return
        }
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .lastOrNull { it.getString("name").endsWith(".apk") }
        if (asset == null) {
            status = "새 버전 $version 이 나왔지만 안드로이드용 파일이 아직 없습니다"
            return
        }
        val file = File(folder, "update-$version.apk")
        val size = asset.getLong("size")
        if (!file.exists() || file.length() != size) {
            status = "새 버전 $version 내려받는 중…"
            withContext(Dispatchers.IO) { download(asset.getString("browser_download_url"), file, size) }
        }
        ready = file
        readyVersion = version
        // 노트를 받지 못해도 설치는 할 수 있다.
        readyNotes = runCatching { withContext(Dispatchers.IO) { notesBetween(current, version) } }.getOrDefault("")
        status = "새 버전 $version 설치 준비됨"
        ask()
    }

    private fun fetch(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) throw IOException("GitHub 응답 ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** from보다 높고 to 이하인 버전의 릴리스 노트를 새 버전부터 차례로 모은다. */
    private fun notesBetween(from: String, to: String): String {
        val releases = JSONArray(fetch(RELEASES))
        return (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .mapNotNull { r ->
                val version = r.optString("tag_name").removePrefix("v")
                if (!newer(version, from) || newer(version, to)) return@mapNotNull null
                // 마크다운 표시(**굵게**, ## 제목, `코드`)를 걷어 낸다.
                val body = r.optString("body")
                    .replace("**", "").replace("`", "")
                    .replace(Regex("(?m)^#+\\s*"), "")
                    .replace(Regex("(?m)^- "), "• ")
                    .trim()
                "[$version]\n$body"
            }
            .joinToString("\n\n")
    }

    private fun download(address: String, file: File, size: Long) {
        folder.mkdirs()
        val part = File(folder, file.name + ".part")
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 65536) } }
        } finally {
            conn.disconnect()
        }
        if (part.length() != size) {
            part.delete()
            throw IOException("내려받은 파일 크기가 맞지 않습니다")
        }
        if (!part.renameTo(file)) throw IOException("파일을 저장하지 못했습니다")
    }

    private fun ask() {
        if (ready == null || activity.isFinishing) return
        if (!resumed) {
            askWhenResumed = true
            return
        }
        askWhenResumed = false
        AlertDialog.Builder(activity)
            .setTitle("NOL 예매도우미 업데이트")
            .setMessage("새 버전 $readyVersion 을 내려받았습니다. 지금 설치할까요?\n(설치하면 앱이 닫힙니다. 다시 열면 새 버전입니다.)" +
                if (readyNotes.isEmpty()) "" else "\n\n업데이트 내용\n$readyNotes")
            .setPositiveButton("설치") { _, _ -> install() }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun install() {
        val file = ready ?: return
        // 처음 한 번은 "이 출처의 앱 설치 허용"을 켜야 한다.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            Toast.makeText(activity, "NOL 예매도우미가 업데이트를 설치할 수 있게 허용한 뒤 돌아오세요", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val installer = activity.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(activity.packageName)
                    val id = installer.createSession(params)
                    installer.openSession(id).use { session ->
                        file.inputStream().use { input ->
                            session.openWrite("update.apk", 0, file.length()).use { out ->
                                input.copyTo(out, 65536)
                                session.fsync(out)
                            }
                        }
                        // 결과(사용자 확인 필요, 실패)는 SetupActivity.onNewIntent로 돌아온다.
                        val result = Intent(activity, SetupActivity::class.java)
                            .setAction(ACTION_INSTALL_STATUS)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        val pending = PendingIntent.getActivity(activity, id, result,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        session.commit(pending.intentSender)
                    }
                }
            } catch (e: Exception) {
                status = "설치 실패: ${e.message}"
                Toast.makeText(activity, status, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 설치 과정에서 돌아온 결과. 시스템이 확인 창을 띄우라고 하면 띄운다. */
    fun onInstallStatus(intent: Intent) {
        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (result != PackageInstaller.STATUS_SUCCESS) {
            status = if (result == PackageInstaller.STATUS_FAILURE_ABORTED) "설치를 취소했습니다"
            else "설치 실패: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
            Toast.makeText(activity, status, Toast.LENGTH_LONG).show()
        }
    }
}
