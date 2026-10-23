package com.logicalcode.sahhab

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File
import java.util.UUID

/** أدوات مشتركة بين الشاشة الرئيسية والمتصفح. */
object Engine {

    /** صفحات نعرف أنها صفحة فيديو واحد. */
    private val VIDEO_PAGE = listOf(
        Regex("youtube\\.com/(watch|shorts/|live/)"), Regex("youtu\\.be/"),
        Regex("facebook\\.com/.+/videos/"), Regex("facebook\\.com/(watch|reel/|share/[rv]/|video\\.php|story\\.php)"),
        Regex("fb\\.watch/"), Regex("instagram\\.com/(reel|reels|p|tv)/"),
        Regex("tiktok\\.com/.+/video/"), Regex("vm\\.tiktok\\.com/"), Regex("(x|twitter)\\.com/.+/status/"),
        Regex("vimeo\\.com/\\d+"), Regex("dailymotion\\.com/video/")
    )

    fun isVideoPage(url: String?): Boolean = url != null && VIDEO_PAGE.any { it.containsMatchIn(url) }

    /** ينقل تسجيل الدخول من المتصفح الداخلي إلى محرك التنزيل (مهم لفيسبوك وإنستغرام). */
    fun writeCookies(ctx: Context, url: String): String? {
        return try {
            val host = java.net.URL(url).host ?: return null
            val parts = host.split(".")
            val base = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
            val cm = CookieManager.getInstance()
            val sources = linkedSetOf("https://$host", "https://www.$base", "https://m.$base", "https://$base")
            val seen = HashSet<String>()
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            for (src in sources) {
                val raw = cm.getCookie(src) ?: continue
                for (pair in raw.split(";")) {
                    val i = pair.indexOf('=')
                    if (i <= 0) continue
                    val name = pair.substring(0, i).trim()
                    val value = pair.substring(i + 1).trim()
                    if (!seen.add(name)) continue
                    sb.append(".").append(base).append("\tTRUE\t/\tTRUE\t0\t").append(name).append("\t").append(value).append("\n")
                }
            }
            if (seen.isEmpty()) return null
            val dir = File(ctx.filesDir, "cookies").apply { mkdirs() }
            val f = File(dir, UUID.randomUUID().toString().take(10) + ".txt")
            f.writeText(sb.toString())
            f.absolutePath
        } catch (e: Exception) { null }
    }

    fun getInfo(url: String, cookieFile: String?): VideoInfo {
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        if (cookieFile != null) req.addOption("--cookies", cookieFile)
        return YoutubeDL.getInfo(req)
    }

    fun qualities(info: VideoInfo): List<Pair<String, String>> {
        val heights = (info.formats ?: arrayListOf())
            .filter { (it.vcodec ?: "none") != "none" && it.height > 0 }
            .map { it.height }.distinct()
        val out = mutableListOf("best" to "أفضل جودة")
        for (h in listOf(2160, 1440, 1080, 720, 480, 360)) {
            if (heights.any { it in (h * 0.8).toInt()..h }) out.add("h$h" to "${h}p")
        }
        out.add("audio" to "صوت فقط MP3")
        return out
    }

    fun start(ctx: Context, url: String, title: String, quality: String, cookieFile: String?) {
        val t = Task(UUID.randomUUID().toString().take(12), url, title, quality, State.QUEUED, System.currentTimeMillis())
        t.cookieFile = cookieFile
        Store.add(t)
        DownloadService.start(ctx, t.id)
    }

    fun friendly(raw: String): String {
        val line = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim()
            ?: raw.lines().lastOrNull { it.isNotBlank() } ?: raw
        return when {
            line.contains("Unsupported URL", true) -> "لم أجد فيديو في هذه الصفحة. افتح الفيديو نفسه ثم اضغط تنزيل."
            line.contains("Sign in to confirm", true) || line.contains("confirm you", true) ->
                "الموقع طلب التحقق. حدّث محرك التنزيل من الشاشة الرئيسية ثم أعد المحاولة."
            line.contains("Private", true) || line.contains("login", true) || line.contains("log in", true) || line.contains("cookies", true) ->
                "هذا الفيديو يحتاج تسجيل دخول. سجّل دخولك في الموقع من متصفح سحّاب ثم أعد المحاولة."
            line.contains("HTTP Error 403", true) -> "الموقع رفض الطلب. حدّث المحرك ثم أعد المحاولة."
            line.contains("HTTP Error 404", true) -> "الفيديو غير موجود، ربما حُذف."
            line.contains("resolve host", true) || line.contains("unreachable", true) || line.contains("timed out", true) ->
                "لا يوجد اتصال بالإنترنت، أو الاتصال ضعيف."
            line.contains("DRM", true) -> "هذا الفيديو مشفّر ولا يمكن تنزيله."
            else -> line.take(220)
        }
    }
}
