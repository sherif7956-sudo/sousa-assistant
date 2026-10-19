package com.sousa.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private class AppInfo(val names: List<String>, val label: String, val pkg: String?, val web: String)

    private val apps = listOf(
        AppInfo(listOf("يوتيوب", "youtube"), "يوتيوب", "com.google.android.youtube", "https://m.youtube.com"),
        AppInfo(listOf("تيك توك", "تيكتوك", "tiktok"), "تيك توك", "com.zhiliaoapp.musically", "https://www.tiktok.com"),
        AppInfo(listOf("واتساب", "واتس اب", "واتس", "whatsapp"), "واتساب", "com.whatsapp", "https://web.whatsapp.com"),
        AppInfo(listOf("انستجرام", "انستا", "instagram"), "انستجرام", "com.instagram.android", "https://www.instagram.com"),
        AppInfo(listOf("فيسبوك", "فيس بوك", "فيس", "facebook"), "فيسبوك", "com.facebook.katana", "https://m.facebook.com"),
        AppInfo(listOf("جوجل", "google"), "جوجل", null, "https://www.google.com")
    )

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var histBox: LinearLayout
    private lateinit var filesView: TextView
    private lateinit var micBtn: Button
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var recognizer: SpeechRecognizer? = null
    private val hist = mutableListOf<Triple<String, String, String>>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun norm(s: String) = s.replace(Regex("[\u064B-\u0652\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا").replace("ة", "ه").replace("ى", "ي")
        .replace(Regex("\\s+"), " ").trim().lowercase()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        tts = TextToSpeech(this, this)
        loadHist()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "سوسة أسيستنت"; textSize = 22f })
        micBtn = Button(this).apply { text = "🎤 اتكلم"; setOnClickListener { onMic() } }
        root.addView(micBtn)
        input = EditText(this).apply {
            hint = "اكتب الأمر هنا، مثلاً: افتح يوتيوب"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { execute(text.toString()); true } else false
            }
        }
        root.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "تنفيذ"; setOnClickListener { execute(input.text.toString()) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "إيقاف"; setOnClickListener { stopAll() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(Button(this).apply { text = "📁 اختار فيديوهات/صور"; setOnClickListener { pickFiles() } })
        filesView = TextView(this)
        root.addView(filesView)
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)
        root.addView(TextView(this).apply { text = "سجل العمليات"; textSize = 18f })
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(histBox)
        setContentView(ScrollView(this).apply { addView(root) })
        renderHist()
    }

    override fun onInit(st: Int) {
        if (st != TextToSpeech.SUCCESS) return
        var r = tts?.setLanguage(Locale("ar", "EG"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = tts?.setLanguage(Locale("ar"))
        }
        ttsOk = !(r == null || r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
    }

    // ---------- السجل ----------
    private fun loadHist() {
        val raw = getSharedPreferences("sousa", MODE_PRIVATE).getString("h", "") ?: ""
        raw.split("\u001E").forEach { e ->
            val p = e.split("\u001F")
            if (p.size == 3) hist.add(Triple(p[0], p[1], p[2]))
        }
    }

    private fun saveHist() {
        val raw = hist.take(50).joinToString("\u001E") { it.first + "\u001F" + it.second + "\u001F" + it.third }
        getSharedPreferences("sousa", MODE_PRIVATE).edit().putString("h", raw).apply()
    }

    private fun renderHist() {
        histBox.removeAllViews()
        if (hist.isEmpty()) {
            histBox.addView(TextView(this).apply { text = "لسه مفيش عمليات." })
            return
        }
        hist.forEach { h ->
            histBox.addView(TextView(this).apply {
                text = "${h.first}  ${h.second}\n${h.third}"
                setPadding(0, dp(8), 0, dp(8))
            })
        }
    }

    private fun report(cmd: String, msg: String, say: Boolean = true) {
        status.text = msg
        hist.add(0, Triple(timeFmt.format(Date()), cmd, msg))
        saveHist()
        renderHist()
        if (say && ttsOk) {
            tts?.speak(msg.dropWhile { !it.isLetter() }, TextToSpeech.QUEUE_FLUSH, null, "sousa")
        }
    }

    // ---------- تنفيذ الأوامر ----------
    private fun openUrl(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true
    } catch (e: Exception) { false }

    private fun openApp(cmd: String, a: AppInfo) {
        val launch = a.pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            try {
                startActivity(launch)
                report(cmd, "⚠️ طلبت من النظام يفتح ${a.label} والنظام قبل الأمر، بس مقدرش أضمن إنه ظهر على الشاشة. بص عليها.")
            } catch (e: Exception) {
                report(cmd, "❌ فشل فتح ${a.label}: ${e.message}")
            }
        } else if (openUrl(a.web)) {
            report(cmd, "⚠️ ${a.label} مش متسطب أو مش ظاهر للتطبيق، فتحت موقعه في المتصفح.")
        } else {
            report(cmd, "❌ مقدرتش أفتح ${a.label}.")
        }
    }

    private fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        val t = norm(cmd)

        if (Regex("(احذف|امسح|شيل|انشر|ابعت|ارسل|ابعث)").containsMatchIn(t)) {
            report(cmd, "❌ ده أمر حساس (حذف/نشر/إرسال) ومش مدعوم لسه. ولما نضيفه هطلب تأكيدك الأول.")
            return
        }
        Regex("^(?:ابحث|دور)(?: في)?(?: (يوتيوب|جوجل))?(?: عن)? (.+)$").find(t)?.let { m ->
            val q = Uri.encode(m.groupValues[2])
            val url = if (m.groupValues[1] == "يوتيوب") "https://m.youtube.com/results?search_query=$q"
                      else "https://www.google.com/search?q=$q"
            if (openUrl(url)) report(cmd, "⚠️ فتحت البحث عن ${m.groupValues[2]}. بص على شاشتك للتأكد.")
            else report(cmd, "❌ مقدرتش أفتح البحث.")
            return
        }
        Regex("^(?:افتح|شغل|روح علي|روح)(?: موقع)? (.+)$").find(t)?.let { m ->
            val rest = m.groupValues[1]
            val app = apps.firstOrNull { a -> a.names.any { norm(it) == rest } }
            if (app != null) { openApp(cmd, app); return }
            if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$").matches(rest)) {
                if (openUrl("https://$rest")) report(cmd, "⚠️ فتحت $rest. بص على شاشتك للتأكد.")
                else report(cmd, "❌ مقدرتش أفتح الموقع.")
                return
            }
            report(cmd, "❌ معرفتش $rest. المدعوم: يوتيوب، تيك توك، واتساب، انستجرام، فيسبوك، جوجل.")
            return
        }
        if (Regex("(اختار|اختر|حدد).*(فيديو|صور)").containsMatchIn(t)) { pickFiles(); return }
        report(cmd, "❌ مفهمتش الأمر ده لسه.")
    }

    // ---------- الملفات (قراءة حقيقية) ----------
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 2)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 2) return
        if (res != RESULT_OK || data == null) {
            report("اختيار ملفات", "ℹ️ مفيش ملفات اتختارت.", false)
            return
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val lines = uris.map { describe(it) }
        filesView.text = lines.joinToString("\n\n")
        val bad = lines.count { it.startsWith("❌") }
        report("اختيار ملفات",
            if (bad == 0) "✅ قريت معلومات ${uris.size} ملف فعلاً." else "⚠️ قريت ${uris.size - bad} وفشل $bad.", false)
    }

    private fun describe(uri: Uri): String {
        var name = "ملف"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
                Unit
            }
        } catch (e: Exception) {
            return "❌ $name — مقدرتش أقراه: ${e.message}"
        }
        val mb = if (size >= 0) String.format(Locale.ENGLISH, "%.1f", size / 1048576.0) + " ميجا" else "حجم مجهول"
        val mime = contentResolver.getType(uri) ?: ""
        if (!mime.startsWith("video/")) return "$name — $mb — صورة/ملف"
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            val sec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) / 1000
            val vertical = h > 0 && Math.abs(w.toDouble() / h - 9.0 / 16) < 0.03
            "$name — $mb — ${w}×${h} — $sec ثانية — " + (if (vertical) "طولي 9:16 ✅" else "مش 9:16")
        } catch (e: Exception) {
            "❌ $name — مقدرتش أقرا الفيديو: ${e.message}"
        } finally {
            r.release()
        }
    }

    // ---------- الصوت ----------
    private fun onMic() {
        if (recognizer != null) { stopAll(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code != 1) return
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else report("🎤", "❌ مفيش إذن للميكروفون. فعّله من الإعدادات > التطبيقات > سوسة أسيستنت > الأذونات.", false)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            report("🎤", "❌ الموبايل مفيهوش خدمة تعرف على الصوت. حدّث تطبيق Google.", false)
            return
        }
        val onDevice = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                  else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) { finishListening(); report("🎤", "❌ " + errText(e), false) }
            override fun onResults(b: Bundle?) {
                val txt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishListening()
                if (txt.isNullOrBlank()) report("🎤", "❌ مسمعتش كلام واضح.", false)
                else { input.setText(txt); execute(txt) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        micBtn.text = "🎙️ بسمعك... (دوس للإيقاف)"
        status.text = if (onDevice) "بسمع (على الجهاز، أوفلاين)..." else "بسمع (ممكن يستخدم الإنترنت)..."
        rec.startListening(i)
    }

    private fun errText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "مسمعتش كلام واضح."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "محتاج إنترنت أو حزمة العربي الأوفلاين."
        12, 13 -> "حزمة العربي الأوفلاين مش منزلة. نزّلها من إعدادات الصوت في تطبيق Google."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مفيش إذن للميكروفون."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "الخدمة مشغولة، جرّب تاني."
        else -> "خطأ رقم $e"
    }

    private fun finishListening() {
        recognizer?.destroy()
        recognizer = null
        micBtn.text = "🎤 اتكلم"
    }

    private fun stopAll() {
        recognizer?.cancel()
        finishListening()
        tts?.stop()
        status.text = "اتوقف."
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
