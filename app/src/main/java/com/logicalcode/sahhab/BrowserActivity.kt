package com.logicalcode.sahhab

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** متصفح داخل سحّاب: تفتح فيه أي موقع، ويظهر زر «تنزيل» عندما تكون في صفحة فيها فيديو. */
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        fun open(ctx: Context, url: String?) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).apply { if (url != null) putExtra(EXTRA_URL, url) })
        }
    }

    private val BG = Color.parseColor("#0D1117")
    private val SURFACE = Color.parseColor("#161B22")
    private val INK = Color.parseColor("#F0F3F6")
    private val MUTED = Color.parseColor("#8B949E")
    private val BRAND = Color.parseColor("#3D7BFD")
    private val ON_BRAND = Color.parseColor("#FFFFFF")
    private val LINE = Color.parseColor("#262D36")

    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var bar: ProgressBar
    private lateinit var fab: Button
    private lateinit var home: LinearLayout
    private lateinit var root: FrameLayout
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var pageHasVideo = false
    @Volatile private var overlayShown = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun rounded(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

        // شريط العنوان
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val close = Button(this).apply {
            text = "✕"; textSize = 16f; setTextColor(INK); background = null; stateListAnimator = null
            setOnClickListener { finish() }
        }
        address = EditText(this).apply {
            hint = "ابحث أو اكتب عنوان موقع"; setHintTextColor(MUTED); setTextColor(INK); textSize = 14f
            background = rounded(SURFACE, 10, LINE); setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; textDirection = View.TEXT_DIRECTION_LTR
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setSelectAllOnFocus(true)
        }
        top.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(address, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(top)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(BRAND); visibility = View.GONE
        }
        col.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        val stage = FrameLayout(this)
        web = WebView(this)
        stage.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        home = buildHome()
        stage.addView(home, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)

        // زر التنزيل العائم
        fab = Button(this).apply {
            text = "⬇  تنزيل الفيديو"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
            setTextColor(ON_BRAND); elevation = dp(8).toFloat()
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#5A92FF"), Color.parseColor("#2F67F0"))).apply { cornerRadius = dp(28).toFloat() }, null)
            setPadding(dp(22), 0, dp(22), 0); visibility = View.GONE
            setOnClickListener { onDownload() }
        }
        root.addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "SahhabNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val s = req.url.scheme ?: ""
                return s != "http" && s != "https"   // نبقى داخل المتصفح ولا نفتح تطبيقات أخرى
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { pageHasVideo = false; overlayShown = false; onUrl(url) }
            override fun onPageFinished(view: WebView, url: String) { onUrl(url); view.evaluateJavascript(OVERLAY_JS, null) }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { onUrl(url) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                bar.progress = p; bar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                fullView = view; fullCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                fab.bringToFront(); updateFab()
            }
            override fun onHideCustomView() { exitFull() }
        }

        // نفحص الصفحة كل ثانيتين: هل فيها فيديو؟
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    if (home.visibility != View.VISIBLE) web.evaluateJavascript("document.querySelector('video')?1:0") { r ->
                        val has = r == "1"
                        if (has != pageHasVideo) { pageHasVideo = has; updateFab() }
                        if (has) web.evaluateJavascript(OVERLAY_JS, null)
                    }
                    main.postDelayed(this, 2000)
                }
            }
        }, 2000)

        val start = intent.getStringExtra(EXTRA_URL)
        if (start != null) go(start)
    }

    private fun buildHome(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(20), dp(28), dp(20), dp(20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        box.addView(TextView(this).apply { text = "افتح موقعاً"; textSize = 22f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD })
        box.addView(TextView(this).apply {
            text = "افتح أي فيديو، وسيظهر لك زر «تنزيل الفيديو» في أسفل الشاشة."
            textSize = 14f; setTextColor(MUTED)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(18) })
        val sites = listOf(
            "يوتيوب" to "https://m.youtube.com", "فيسبوك" to "https://m.facebook.com",
            "إنستغرام" to "https://www.instagram.com", "تيك توك" to "https://www.tiktok.com",
            "إكس" to "https://x.com", "بحث جوجل" to "https://www.google.com"
        )
        var row: LinearLayout? = null
        sites.forEachIndexed { i, (name, url) ->
            if (i % 2 == 0) { row = LinearLayout(this); box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(10) }) }
            val b = Button(this).apply {
                text = name; textSize = 16f; isAllCaps = false; setTextColor(INK); stateListAnimator = null
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), rounded(SURFACE, 16, LINE), null)
                setOnClickListener { go(url) }
            }
            row!!.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i % 2 == 1) marginStart = dp(10) })
        }
        return box
    }

    private fun go(raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(".") && !s.contains(" ") -> "https://$s"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        }
        home.visibility = View.GONE
        web.loadUrl(url)
        address.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun onUrl(url: String?) {
        if (url == null || url.startsWith("about:")) return
        if (!address.hasFocus()) address.setText(url)
        updateFab()
    }

    private fun updateFab() {
        // الزر السفلي احتياطي: يظهر في ملء الشاشة، أو إذا لم يظهر الزر فوق الفيديو
        val show = home.visibility != View.VISIBLE && (fullView != null || (!overlayShown && (Engine.isVideoPage(web.url) || pageHasVideo)))
        fab.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun exitFull() {
        main.post { updateFab() }
        fullView?.let { root.removeView(it) }
        fullView = null
        fullCallback?.onCustomViewHidden(); fullCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    // ---------- التنزيل ----------
    private val FIND_VIDEO_JS = """
        (function(){
          var vs = Array.prototype.slice.call(document.querySelectorAll('video'));
          var best = null, area = 0, H = window.innerHeight, W = window.innerWidth;
          vs.forEach(function(v){
            var r = v.getBoundingClientRect();
            var w = Math.max(0, Math.min(r.right, W) - Math.max(r.left, 0));
            var h = Math.max(0, Math.min(r.bottom, H) - Math.max(r.top, 0));
            var a = w * h + (v.paused ? 0 : 1e7);
            if (a > area) { area = a; best = v; }
          });
          var link = '', src = '';
          if (best) {
            src = best.currentSrc || best.src || '';
            var el = best;
            for (var i = 0; i < 14 && el && !link; i++) {
              el = el.parentElement;
              if (!el) break;
              var a = el.querySelector('a[href*="/videos/"],a[href*="/reel/"],a[href*="/watch"],a[href*="video.php"],a[href*="story.php"],a[href*="/status/"]');
              if (a) link = a.href;
            }
          }
          return JSON.stringify({link: link, src: src});
        })();
    """.trimIndent()

    /** زر صغير فوق الفيديو نفسه، في زاويته العليا، مثل ماريا. */
    private val OVERLAY_JS = """
        (function(){
          if (window.__sahhabBtn) return; window.__sahhabBtn = true;
          var b = document.createElement('div');
          b.textContent = '⬇ تنزيل';
          b.style.cssText = 'position:fixed;z-index:2147483647;display:none;background:linear-gradient(#5A92FF,#2F67F0);color:#FFFFFF;' +
            'font:bold 14px sans-serif;padding:7px 13px;border-radius:16px;box-shadow:0 2px 8px rgba(0,0,0,.45);' +
            'direction:rtl;cursor:pointer;-webkit-user-select:none;user-select:none';
          document.documentElement.appendChild(b);
          var cur = null, shown = false;
          function pick(){
            var vs = document.querySelectorAll('video'), best = null, area = 0, H = innerHeight, W = innerWidth;
            for (var i = 0; i < vs.length; i++){
              var r = vs[i].getBoundingClientRect();
              if (r.width < 120 || r.height < 70) continue;
              var w = Math.max(0, Math.min(r.right, W) - Math.max(r.left, 0));
              var h = Math.max(0, Math.min(r.bottom, H) - Math.max(r.top, 0));
              var a = w * h + (vs[i].paused ? 0 : 1e7);
              if (w * h > 0 && a > area){ area = a; best = vs[i]; }
            }
            return best;
          }
          function place(){
            cur = pick();
            var vis = !!cur;
            if (vis){
              var r = cur.getBoundingClientRect();
              b.style.display = 'block';
              b.style.top = Math.max(8, r.top + 8) + 'px';
              b.style.left = Math.max(8, Math.min(r.right, innerWidth) - b.offsetWidth - 8) + 'px';
            } else b.style.display = 'none';
            if (vis !== shown){ shown = vis; try { SahhabNative.overlay(vis); } catch(e){} }
          }
          function info(v){
            var link = '', src = v.currentSrc || v.src || '', el = v;
            for (var i = 0; i < 14 && el && !link; i++){
              el = el.parentElement; if (!el) break;
              var a = el.querySelector('a[href*="/videos/"],a[href*="/reel/"],a[href*="/watch"],a[href*="video.php"],a[href*="story.php"],a[href*="/status/"]');
              if (a) link = a.href;
            }
            return [link, src];
          }
          b.addEventListener('click', function(e){
            e.preventDefault(); e.stopPropagation();
            var v = cur || pick(); var x = v ? info(v) : ['', ''];
            b.textContent = '… جارٍ';
            setTimeout(function(){ b.textContent = '⬇ تنزيل'; }, 2500);
            SahhabNative.download(x[0], x[1]);
          }, true);
          setInterval(place, 600);
          addEventListener('scroll', place, {passive: true});
          place();
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface fun download(link: String?, src: String?) {
            main.post { chooseTarget(link ?: "", src ?: "") }
        }
        @JavascriptInterface fun overlay(visible: Boolean) {
            main.post { overlayShown = visible; updateFab() }
        }
    }

    private fun chooseTarget(link: String, src: String) {
        val page = web.url ?: return
        val target = when {
            Engine.isVideoPage(page) -> page
            Engine.isVideoPage(link) -> link
            src.startsWith("http") -> src
            link.isNotEmpty() -> link
            else -> page
        }
        fetchAndChoose(target, page)
    }

    private fun onDownload() {
        val page = web.url ?: return
        web.evaluateJavascript(FIND_VIDEO_JS) { res ->
            var link = ""; var src = ""
            try {
                // النتيجة تصل نصاً داخل علامات تنصيص، فنفك ترميزها أولاً
                val o = JSONObject(org.json.JSONTokener(res).nextValue() as String)
                link = o.optString("link"); src = o.optString("src")
            } catch (_: Exception) {}
            val target = when {
                Engine.isVideoPage(page) -> page
                Engine.isVideoPage(link) -> link
                src.startsWith("http") -> src
                link.isNotEmpty() -> link
                else -> page
            }
            fetchAndChoose(target, page)
        }
    }

    private fun fetchAndChoose(url: String, page: String) {
        val wait = AlertDialog.Builder(this, R.style.SahhabDialog)
            .setMessage("أقرأ الفيديو…").setCancelable(true).create()
        wait.show()
        val cookies = Engine.writeCookies(this, page)
        Thread {
            var waited = 0
            while (!SahhabApp.engineReady && SahhabApp.engineError == null && waited < 90_000) { Thread.sleep(300); waited += 300 }
            val r = runCatching {
                if (!SahhabApp.engineReady) throw Exception(SahhabApp.engineError ?: "المحرك لم يجهز بعد")
                Engine.getInfo(url, cookies)
            }
            main.post {
                if (isFinishing) return@post
                wait.dismiss()
                r.onSuccess { info ->
                    val title = info.title ?: info.fulltitle ?: "فيديو"
                    val opts = Engine.qualities(info)
                    AlertDialog.Builder(this, R.style.SahhabDialog)
                        .setTitle(title)
                        .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                            Engine.start(this, url, title, opts[which].first, cookies)
                            Toast.makeText(this, "بدأ التنزيل، تابعه في الإشعارات", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }.onFailure {
                    AlertDialog.Builder(this, R.style.SahhabDialog)
                        .setTitle("تعذّر التنزيل")
                        .setMessage(Engine.friendly(it.message ?: it.toString()))
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                fullView != null -> { exitFull(); return true }
                home.visibility != View.VISIBLE && web.canGoBack() -> { web.goBack(); return true }
                home.visibility != View.VISIBLE -> { home.visibility = View.VISIBLE; fab.visibility = View.GONE; address.setText(""); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); web.onPause(); CookieManager.getInstance().flush() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
