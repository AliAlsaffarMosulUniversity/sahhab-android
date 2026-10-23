package com.logicalcode.sahhab

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, id: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id))
        }

        fun cancel(ctx: Context, id: String) {
            val t = Store.get(id) ?: return
            val wasRunning = t.state == State.RUNNING
            t.state = State.CANCELED
            Store.update(t, true)
            if (wasRunning) {
                Thread { try { YoutubeDL.destroyProcessById(id) } catch (_: Exception) {} }.start()
            }
        }
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val active = AtomicInteger(0)
    private val accepted = ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, summary(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent?.action == ACTION_START && id != null && accepted.putIfAbsent(id, true) == null) {
            active.incrementAndGet()
            pool.execute {
                try { runTask(id) } finally {
                    accepted.remove(id)
                    if (active.decrementAndGet() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else refreshNotif(true)
                }
            }
        } else if (active.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- التنزيل ----------
    private fun runTask(id: String) {
        val t = Store.get(id) ?: return
        if (t.state == State.CANCELED) return

        // ننتظر جاهزية المحرك عند أول تشغيل
        var waited = 0
        while (!SahhabApp.engineReady) {
            SahhabApp.engineError?.let { fail(t, "تعذّر تشغيل محرك التنزيل: $it"); return }
            Thread.sleep(300); waited += 300
            if (waited > 120_000) { fail(t, "المحرك لم يجهز بعد، أعد المحاولة بعد قليل"); return }
        }

        t.state = State.RUNNING; t.progress = 0f; t.error = null; t.line = "يبدأ التنزيل…"
        Store.update(t, true)
        refreshNotif(true)

        val work = File(getExternalFilesDir(null) ?: filesDir, "work/${t.id}").apply { mkdirs() }
        val pathFile = File(work, "path.txt").apply { delete() }

        val req = YoutubeDLRequest(t.url)
        req.addOption("-o", work.absolutePath + "/%(title).120B.%(ext)s")
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--concurrent-fragments", 8)
        req.addOption("--retries", 10)
        req.addOption("--fragment-retries", 10)
        t.cookieFile?.let { if (File(it).exists()) req.addOption("--cookies", it) }
        when {
            t.quality == "audio" -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            }
            t.quality.startsWith("h") -> {
                val h = t.quality.substring(1)
                req.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b")
                req.addOption("-S", "ext:mp4:m4a")
                req.addOption("--merge-output-format", "mp4")
            }
            else -> {
                req.addOption("-f", "bv*+ba/b")
                req.addOption("-S", "ext:mp4:m4a")
                req.addOption("--merge-output-format", "mp4")
            }
        }
        req.addCommands(listOf("--print-to-file", "after_move:filepath", pathFile.absolutePath))

        var lastUi = 0L
        try {
            YoutubeDL.execute(req, t.id, false) { p, eta, line ->
                if (p >= 0f) t.progress = p
                t.eta = eta
                t.line = line
                val now = SystemClock.uptimeMillis()
                if (now - lastUi > 600) {
                    lastUi = now
                    Store.update(t, false)
                    refreshNotif(false)
                }
            }
            if (t.state == State.CANCELED) { work.deleteRecursively(); return }

            val out = pathFile.takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() }?.trim()
                ?.let { File(it) }?.takeIf { it.exists() }
                ?: work.listFiles()?.filter { it.isFile && it.name != "path.txt" && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                ?: throw Exception("انتهى التنزيل لكن لم أجد الملف")

            val (uri, mime) = saveToDownloads(out)
            t.fileUri = uri.toString(); t.fileName = out.name; t.mime = mime
            t.state = State.DONE; t.progress = 100f; t.line = ""
            Store.update(t, true)
            notifyDone(t)
            work.deleteRecursively()
        } catch (e: YoutubeDL.CanceledException) {
            t.state = State.CANCELED; Store.update(t, true)
            work.deleteRecursively()
        } catch (e: Exception) {
            if (t.state == State.CANCELED) { work.deleteRecursively(); return }
            // نُبقي الملفات الجزئية حتى تكمل «أعد المحاولة» من حيث توقفت
            fail(t, Engine.friendly(e.message ?: e.toString()))
        }
    }

    private fun fail(t: Task, msg: String) {
        t.state = State.ERROR; t.error = msg
        Store.update(t, true)
    }

    private fun friendlyError(raw: String): String {
        val line = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: raw.lines().lastOrNull { it.isNotBlank() } ?: raw
        return when {
            line.contains("Unsupported URL", true) -> "هذا الرابط غير مدعوم. تأكد أنه رابط صفحة فيديو أو ملف."
            line.contains("Sign in to confirm", true) || line.contains("confirm you", true) ->
                "الموقع طلب التحقق من أنك لست برنامجاً آلياً. حدّث محرك التنزيل من أسفل الصفحة ثم أعد المحاولة."
            line.contains("Private video", true) || line.contains("login", true) || line.contains("log in", true) ->
                "هذا الفيديو خاص أو يحتاج تسجيل دخول."
            line.contains("HTTP Error 403", true) -> "الموقع رفض الطلب. حدّث المحرك ثم أعد المحاولة."
            line.contains("HTTP Error 404", true) -> "الفيديو غير موجود، ربما حُذف."
            line.contains("Unable to resolve host", true) || line.contains("Network is unreachable", true) || line.contains("timed out", true) ->
                "لا يوجد اتصال بالإنترنت، أو الاتصال ضعيف."
            line.contains("DRM", true) -> "هذا الفيديو محمي بالتشفير ولا يمكن تنزيله."
            else -> line.take(220)
        }
    }

    // ---------- الحفظ في مجلد التنزيلات ----------
    private fun saveToDownloads(f: File): Pair<Uri, String> {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sahhab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("تعذّر الحفظ في مجلد التنزيلات")
        contentResolver.openOutputStream(uri)!!.use { os -> f.inputStream().use { it.copyTo(os, 1 shl 16) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri to mime
    }

    // ---------- الإشعارات ----------
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun summary(): Notification {
        val running = Store.all().filter { it.state == State.RUNNING }
        val b = Notification.Builder(this, SahhabApp.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (running.isEmpty()) {
            b.setContentTitle("سحّاب").setContentText("يجهّز التنزيل…").setProgress(0, 0, true)
        } else {
            val t = running.first()
            val more = if (running.size > 1) " (+${running.size - 1})" else ""
            b.setContentTitle(t.title + more)
            b.setContentText("${t.progress.toInt()}%" + if (t.eta > 0) " — المتبقي ${fmtEta(t.eta)}" else "")
            b.setProgress(100, t.progress.toInt(), t.progress <= 0f)
        }
        return b.build()
    }

    private fun refreshNotif(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastNotif < 1000) return
        lastNotif = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, summary())
    }

    private fun notifyDone(t: Task) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.fileUri), t.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, t.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, SahhabApp.CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("اكتمل التنزيل")
            .setContentText(t.title)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(t.id.hashCode(), n)
    }

    private fun fmtEta(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
