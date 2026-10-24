package com.logicalcode.sahhab

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : Activity() {

    // الألوان: نفس هوية سحّاب
    private val BG = Color.parseColor("#0D1117")
    private val SURFACE = Color.parseColor("#161B22")
    private val INK = Color.parseColor("#F0F3F6")
    private val MUTED = Color.parseColor("#8B949E")
    private val LINE = Color.parseColor("#262D36")
    private val BRAND = Color.parseColor("#3D7BFD")
    private val ON_BRAND = Color.parseColor("#FFFFFF")
    private val EDGE = Color.parseColor("#F5A524")
    private val DANGER = Color.parseColor("#F85149")
    private val OK = Color.parseColor("#3FB950")
    private val SURFACE2 = Color.parseColor("#1F2630")
    private val BRAND_TOP = Color.parseColor("#5A92FF")
    private val BRAND_BOTTOM = Color.parseColor("#2F67F0")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var findBtn: Button
    private lateinit var engineText: TextView
    private lateinit var infoBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private val onStore: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() { super.onStart(); Store.listen(onStore); render() }
    override fun onStop() { Store.unlisten(onStore); super.onStop() }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text) ?: return
        input.setText(url)
        fetchInfo(url)
    }

    private fun extractUrl(s: String): String? = Regex("https?://\\S+").find(s)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    // ---------- بناء الواجهة ----------
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Int = 12, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun text(s: String, size: Float = 15f, color: Int = INK, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
    }

    private fun gradient(radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BRAND_TOP, BRAND_BOTTOM)).apply { cornerRadius = dp(radius).toFloat() }

    // تأثير لمس ناعم فوق أي خلفية
    private fun ripple(bg: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(50, 255, 255, 255)), bg, null)

    private fun tint(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun button(label: String, primary: Boolean = false, color: Int = INK, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; letterSpacing = 0.01f
        stateListAnimator = null
        minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        when {
            primary -> { background = ripple(gradient(14)); setTextColor(ON_BRAND); typeface = Typeface.DEFAULT_BOLD; elevation = dp(2).toFloat() }
            color == DANGER -> { background = ripple(rounded(tint(DANGER, 38), 14)); setTextColor(DANGER) }
            else -> { background = ripple(rounded(SURFACE2, 14)); setTextColor(color) }
        }
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        // الترويسة: الأيقونة والاسم
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("سحّاب", 28f, INK, true).apply { letterSpacing = -0.01f })
        titles.addView(text("نزّل الفيديو والملفات من أي موقع", 13f, MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        col.addView(head)

        // خانة الرابط
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(SURFACE, 18, LINE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        input = EditText(this).apply {
            hint = "https://…"
            setHintTextColor(MUTED); setTextColor(INK); textSize = 15f
            background = null
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            textDirection = View.TEXT_DIRECTION_LTR
            isSingleLine = true
            setOnEditorActionListener { _, _, _ -> onFind(); true }
        }
        val paste = button("لصق") {
            val cm = getSystemService(ClipboardManager::class.java)
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            val u = extractUrl(t)
            if (u == null) toast("لا يوجد رابط في الحافظة") else { input.setText(u); fetchInfo(u) }
        }
        row.addView(paste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        findBtn = button("ابحث", primary = true) { onFind() }
        row.addView(findBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        col.addView(row, lp(top = 22))

        // المتصفح الداخلي: أسهل طريقة للتنزيل من يوتيوب وفيسبوك
        val browse = button("🌐  افتح المتصفح ونزّل من يوتيوب وفيسبوك", primary = true) { BrowserActivity.open(this, null) }
        browse.textSize = 15f
        col.addView(browse, lp(h = dp(52), top = 12))

        engineText = text("", 13f, MUTED)
        col.addView(engineText, lp(top = 8))

        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 18, LINE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        col.addView(infoBox, lp(top = 16))

        col.addView(text("التنزيلات", 18f, INK, true), lp(top = 26))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, lp(top = 8))

        // أسفل الصفحة: تحديث المحرك
        col.addView(View(this).apply { setBackgroundColor(LINE) }, lp(h = dp(1), top = 28))
        col.addView(text("المواقع تغيّر طريقتها أحياناً. إذا فشل تنزيل كان يعمل، حدّث المحرك.", 13f, MUTED), lp(top = 12))
        col.addView(button("حدّث محرك التنزيل") { updateEngine() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = 8))
        col.addView(text("سحّاب — من أعمال لوجيكال كود، الموصل.\nنزّل للاستعمال الشخصي، واحترم حقوق أصحاب المحتوى عند إعادة النشر.", 12f, MUTED), lp(top = 18))

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
        }
    }

    // ---------- البحث عن الفيديو ----------
    private fun onFind() {
        val u = extractUrl(input.text.toString())
        if (u == null) toast("الرابط غير صحيح، يجب أن يبدأ بـ https://") else fetchInfo(u)
    }

    private fun fetchInfo(url: String) {
        infoBox.visibility = View.VISIBLE
        infoBox.removeAllViews()
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
        row.addView(text("أقرأ معلومات الرابط…", 15f, MUTED), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        infoBox.addView(row)
        findBtn.isEnabled = false

        Thread {
            var waited = 0
            while (!SahhabApp.engineReady && SahhabApp.engineError == null && waited < 90_000) { Thread.sleep(300); waited += 300 }
            val result: Result<VideoInfo> = runCatching {
                if (!SahhabApp.engineReady) throw Exception(SahhabApp.engineError ?: "المحرك لم يجهز بعد")
                YoutubeDL.getInfo(YoutubeDLRequest(url).addOption("--no-playlist"))
            }
            main.post {
                findBtn.isEnabled = true
                result.onSuccess { showInfo(url, it) }
                    .onFailure { showError(it.message ?: it.toString()) }
            }
        }.start()
    }

    private fun showError(raw: String) {
        infoBox.removeAllViews()
        val line = raw.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: raw.lines().lastOrNull { it.isNotBlank() } ?: raw
        val msg = when {
            line.contains("Unsupported URL", true) -> "هذا الرابط غير مدعوم. جرّب رابط صفحة الفيديو نفسها."
            line.contains("Sign in", true) || line.contains("confirm you", true) -> "الموقع طلب التحقق. حدّث المحرك من أسفل الصفحة ثم أعد المحاولة."
            line.contains("Private", true) || line.contains("login", true) -> "هذا الفيديو خاص أو يحتاج تسجيل دخول."
            line.contains("resolve host", true) || line.contains("timed out", true) -> "لا يوجد اتصال بالإنترنت، أو الاتصال ضعيف."
            else -> line.take(240)
        }
        infoBox.addView(text("تعذّر قراءة الرابط", 16f, DANGER, true))
        infoBox.addView(text(msg, 14f, INK), lp(top = 6))
    }

    private fun showInfo(url: String, info: VideoInfo) {
        infoBox.removeAllViews()
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BG, 10)
            clipToOutline = true
            visibility = View.GONE
        }
        infoBox.addView(thumb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)).apply { bottomMargin = dp(10) })
        info.thumbnail?.let { loadImage(it, thumb) }

        val title = info.title ?: info.fulltitle ?: url
        infoBox.addView(text(title, 16f, INK, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        val meta = listOfNotNull(info.uploader, info.extractorKey, if (info.duration > 0) fmtDur(info.duration) else null).joinToString("  •  ")
        if (meta.isNotEmpty()) infoBox.addView(text(meta, 13f, MUTED), lp(top = 4))

        infoBox.addView(text("اختر الجودة", 14f, MUTED), lp(top = 14))
        val heights = (info.formats ?: arrayListOf())
            .filter { (it.vcodec ?: "none") != "none" && it.height > 0 }
            .map { it.height }.distinct().sortedDescending()
        val options = mutableListOf("best" to "أفضل جودة")
        for (h in listOf(2160, 1440, 1080, 720, 480, 360)) {
            if (heights.any { it >= h } && heights.any { it in (h * 0.8).toInt()..h }) options.add("h$h" to "${h}p")
        }
        options.add("audio" to "صوت فقط MP3")

        var row: LinearLayout? = null
        options.forEachIndexed { i, (key, label) ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                infoBox.addView(row, lp(top = 8))
            }
            val b = button(label, primary = i == 0) { startDownload(url, title, key) }
            row!!.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i % 3 != 0) marginStart = dp(8) })
        }
        // نكمل الصف الأخير بفراغ ليبقى توزيع الأزرار متساوياً
        val rem = options.size % 3
        if (rem != 0) repeat(3 - rem) { row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }) }
    }

    private fun loadImage(src: String, into: ImageView) {
        Thread {
            val bmp: Bitmap? = try {
                val c = URL(src).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (bmp != null) main.post { into.setImageBitmap(bmp); into.visibility = View.VISIBLE }
        }.start()
    }

    private fun startDownload(url: String, title: String, quality: String) {
        val t = Task(UUID.randomUUID().toString().take(12), url, title, quality, State.QUEUED, System.currentTimeMillis())
        Store.add(t)
        DownloadService.start(this, t.id)
        infoBox.visibility = View.GONE
        input.setText("")
        toast("بدأ التنزيل")
    }

    // ---------- قائمة التنزيلات ----------
    private fun render() {
        engineText.text = when {
            SahhabApp.engineError != null -> "تعذّر تشغيل المحرك: ${SahhabApp.engineError}"
            !SahhabApp.engineReady -> "يجهّز محرك التنزيل لأول مرة، قد يأخذ دقيقة…"
            else -> "المحرك جاهز" + (SahhabApp.engineVersion?.let { " — الإصدار $it" } ?: "")
        }
        engineText.setTextColor(if (SahhabApp.engineError != null) DANGER else MUTED)

        val items = Store.all()
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(text("لا توجد تنزيلات بعد.\nالصق رابطاً في الأعلى، أو من يوتيوب وغيره اضغط «مشاركة» واختر سحّاب.", 14f, MUTED).apply {
                background = rounded(Color.TRANSPARENT, 14, LINE)
                setPadding(dp(16), dp(20), dp(16), dp(20))
                gravity = Gravity.CENTER
            })
            return
        }
        for (t in items) listBox.addView(itemView(t), lp(top = 10))
    }

    private fun itemView(t: Task): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 18, LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(text(t.title, 15f, INK, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val q = when { t.quality == "audio" -> "صوت"; t.quality.startsWith("h") -> t.quality.drop(1) + "p"; else -> "أفضل جودة" }
        val (status, color) = when (t.state) {
            State.QUEUED -> "بانتظار دوره  •  $q" to MUTED
            State.RUNNING -> "${t.progress.toInt()}%" + (if (t.eta > 0) "  •  المتبقي ${fmtDur(t.eta.toInt())}" else "") + "  •  $q" to EDGE
            State.DONE -> "مكتمل  •  محفوظ في مجلد التنزيلات/Sahhab" to OK
            State.ERROR -> (t.error ?: "فشل التنزيل") to DANGER
            State.CANCELED -> "أُلغي" to MUTED
        }
        // حالة التنزيل كشارة ملوّنة
        card.addView(text(status, 12.5f, color).apply {
            background = rounded(tint(color, 34), 8); setPadding(dp(10), dp(4), dp(10), dp(4))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (t.state == State.RUNNING || t.state == State.QUEUED) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = t.progress.toInt()
                isIndeterminate = t.state == State.QUEUED || t.progress <= 0f
                progressTintList = android.content.res.ColorStateList.valueOf(BRAND)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(SURFACE2)
            }
            card.addView(bar, lp(top = 8))
        }
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(label: String, c: Int = INK, primary: Boolean = false, f: () -> Unit) {
            acts.addView(button(label, primary, c, f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(8) })
        }
        when (t.state) {
            State.RUNNING, State.QUEUED -> act("إلغاء", DANGER) { DownloadService.cancel(this, t.id) }
            State.DONE -> { act("افتح", primary = true) { openFile(t) }; act("شارك") { shareFile(t) } }
            State.ERROR, State.CANCELED -> act("أعد المحاولة", primary = true) {
                t.state = State.QUEUED; t.error = null; Store.update(t, true); DownloadService.start(this, t.id)
            }
        }
        if (t.state != State.RUNNING && t.state != State.QUEUED) act("احذف من القائمة", MUTED) { Store.remove(t.id) }
        card.addView(acts, lp(top = 10))
        return card
    }

    private fun openFile(t: Task) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.fileUri), t.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("لا يوجد تطبيق يفتح هذا النوع من الملفات") }
    }

    private fun shareFile(t: Task) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType(t.mime ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(t.fileUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "شارك الملف"))
        } catch (e: Exception) { toast("تعذّرت المشاركة") }
    }

    private fun updateEngine() {
        if (!SahhabApp.engineReady) { toast("انتظر حتى يجهز المحرك"); return }
        toast("يحدّث المحرك…")
        Thread {
            val r = runCatching { YoutubeDL.updateYoutubeDL(application, YoutubeDL.UpdateChannel.STABLE) }
            SahhabApp.engineVersion = YoutubeDL.versionName(application)
            main.post {
                r.onSuccess {
                    toast(if (it == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "المحرك محدّث أصلاً" else "حُدّث المحرك بنجاح")
                    getSharedPreferences("sahhab", MODE_PRIVATE).edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                }.onFailure { toast("تعذّر التحديث، تأكد من الإنترنت") }
                render()
            }
        }.start()
    }

    private fun fmtDur(s: Int): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
