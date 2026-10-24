package com.logicalcode.sahhab

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * نافذة صغيرة تظهر فوق يوتيوب أو فيسبوك عند اختيار «نزّل بسحّاب» من قائمة المشاركة.
 * تختار الجودة فيبدأ التنزيل، وتبقى في التطبيق الذي كنت فيه.
 */
class ShareActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val dlgTheme = R.style.SahhabDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = listOfNotNull(
            intent?.getStringExtra(Intent.EXTRA_TEXT),
            intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            intent?.dataString
        ).joinToString(" ")
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
        if (url == null) {
            Toast.makeText(this, "لم أجد رابطاً فيما شاركته", Toast.LENGTH_LONG).show()
            finish(); return
        }
        fetch(url)
    }

    private fun fetch(url: String) {
        var cancelled = false
        val wait = AlertDialog.Builder(this, dlgTheme)
            .setTitle("سحّاب")
            .setMessage(if (SahhabApp.engineReady) "أقرأ الفيديو…" else "يجهّز محرك التنزيل، لحظات…")
            .setNegativeButton("إلغاء") { _, _ -> cancelled = true; finish() }
            .setOnCancelListener { cancelled = true; finish() }
            .create()
        wait.show()

        // إذا سجّلت دخولك في متصفح سحّاب، نستعمل نفس الدخول (مهم لبعض فيديوهات فيسبوك)
        val cookies = Engine.writeCookies(this, url)
        Thread {
            var waited = 0
            while (!SahhabApp.engineReady && SahhabApp.engineError == null && waited < 90_000) { Thread.sleep(300); waited += 300 }
            val r = runCatching {
                if (!SahhabApp.engineReady) throw Exception(SahhabApp.engineError ?: "المحرك لم يجهز بعد")
                Engine.getInfo(url, cookies)
            }
            main.post {
                if (cancelled || isFinishing) return@post
                wait.dismiss()
                r.onSuccess { info ->
                    val title = info.title ?: info.fulltitle ?: "فيديو"
                    val opts = Engine.qualities(info)
                    AlertDialog.Builder(this, dlgTheme)
                        .setTitle("نزّل: $title")
                        .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                            Engine.start(this, url, title, opts[which].first, cookies)
                            Toast.makeText(this, "بدأ التنزيل، تابعه في الإشعارات", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        .setNegativeButton("إلغاء") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }.onFailure {
                    AlertDialog.Builder(this, dlgTheme)
                        .setTitle("تعذّر التنزيل")
                        .setMessage(Engine.friendly(it.message ?: it.toString()))
                        .setPositiveButton("حسناً") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }.start()
    }
}
