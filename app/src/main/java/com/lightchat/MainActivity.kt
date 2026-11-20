package com.lightchat

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lightchat.core.AckTracker
import com.lightchat.core.Assembler
import com.lightchat.core.Crypt
import com.lightchat.core.Frame
import com.lightchat.core.FrameCodec
import com.lightchat.core.LightDecoder
import com.lightchat.core.Msg
import com.lightchat.core.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LightChat: two-phone messenger over flashlight (sender) and rear camera (receiver).
 *
 * - Messages are cut into small chunks (core/Message.kt) that go out back to back in ONE blink
 *   transmission; the receiver shows the text live, chunk by chunk, as soon as each chunk's CRC is OK.
 * - After the transmission the receiver answers with an ACK bit-mask; the sender re-sends only the
 *   missing chunks (selective repeat), up to MAX_ATTEMPTS times.
 * - Speed is automatic (steps up after clean deliveries, down after problems) or fixed.
 * - Extras: quick emergency phrases, SOS beacon (Morse for human eyes + data message),
 *   optional passphrase encryption (off by default), compact Persian encoding, vibration,
 *   signal quality display, glassmorphism UI.
 */
class MainActivity : ComponentActivity() {

    private class Bubble(val view: TextView, val text: String)

    private class OutMessage(val text: String, val bubble: Bubble, val dest: Int)

    private class Member(@Volatile var name: String, @Volatile var uid: Int, @Volatile var lastHeard: Long)

    companion object {
        private const val MAX_ATTEMPTS = 6
        private const val TX_GUARD_MS = 500L
        private const val ACK_DELAY_MS = 450L
        private const val MEMBER_TTL_MS = 15L * 60_000L
        private const val HELLO_EVERY = 8

        private val QUICK = arrayOf(
            "SOS کمک! نیاز به کمک فوری داریم",
            "من سالم و امن هستم",
            "مجروح داریم، آمبولانس لازم است",
            "آب و غذا نیاز داریم",
            "خطر! از اینجا دور شوید",
            "منتظر بمانید، کمک می‌آید",
            "دریافت شد",
            "لطفاً تکرار کنید",
            "بله",
            "نه"
        )
    }

    // ---- UI ----
    private lateinit var previewView: PreviewView
    private lateinit var lampDrawable: GradientDrawable
    private lateinit var statusText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var speedBtn: TextView
    private lateinit var sosBtn: TextView
    private lateinit var emptyHint: TextView
    private lateinit var guideCard: View

    private val prefs by lazy { getSharedPreferences("lightchat", MODE_PRIVATE) }
    private var vibrator: Vibrator? = null

    // ---- camera ----
    @Volatile
    private var camera: Camera? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ---- protocol state ----
    private val decoder = LightDecoder { frame -> onFrameDecoded(frame) }

    @Volatile
    private var txActive = false

    @Volatile
    private var ignoreUntil = 0L

    @Volatile
    private var resetDecoder = true

    // settings (0 = auto, 1..5 = fixed speed index + 1)
    @Volatile
    private var speedMode = 0

    @Volatile
    private var autoIdx = 1

    @Volatile
    private var peerMax = 1
    private var successStreak = 0
    private var chunkPref = Msg.DEFAULT_CHUNK

    @Volatile
    private var key: ByteArray? = null
    private var vibrateOn = true
    private var wantHighFps = false

    private val txMutex = Mutex()
    private val msgMutex = Mutex()
    private val outbox = Channel<OutMessage>(Channel.UNLIMITED)
    private val ackChannel = Channel<Msg.Ack>(Channel.UNLIMITED)
    private var nextMsgId = 0
    private var sosJob: Job? = null

    // ---- receive side (touched on the UI thread only, except the volatile flag) ----
    private val assembler = Assembler()
    private val bubbles = HashMap<Assembler.Message, Bubble>()
    private val pendingAck = ArrayList<Assembler.Message>()
    private var ackJob: Job? = null

    @Volatile
    private var sessionSender = -1 // sender of the transmission being decoded (set on the analyzer thread)

    // ---- group identity ----
    @Volatile
    private var myId = 1 // 1..15, shown to nobody; names are what people see
    private var myUid = 0
    private var myName = ""

    @Volatile
    private var destId = Msg.ALL
    private val members = ConcurrentHashMap<Int, Member>()

    @Volatile
    private var helloDue = true
    private var sentSinceHello = 0
    private lateinit var destBtn: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "بدون اجازه‌ی دوربین، اپ کار نمی‌کند", Toast.LENGTH_LONG).show()
        }

    // =====================================================================================
    // lifecycle
    // =====================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#140A33")
        window.navigationBarColor = Color.parseColor("#06304A")

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        speedMode = prefs.getInt("speedMode", 0).coerceIn(0, FrameCodec.HALF_MS_CHOICES.size)
        vibrateOn = prefs.getBoolean("vibrate", true)
        wantHighFps = prefs.getBoolean("highfps", false)
        nextMsgId = (0..3).random()
        applyPassphrase(prefs.getString("pass", "") ?: "")
        var uid = prefs.getInt("uid", -1)
        if (uid < 0) {
            uid = (0..65535).random()
            prefs.edit().putInt("uid", uid).apply()
        }
        myUid = uid
        var nid = prefs.getInt("nodeId", 0)
        if (nid !in 1..Msg.MAX_NODE) {
            nid = (1..Msg.MAX_NODE).random()
            prefs.edit().putInt("nodeId", nid).apply()
        }
        myId = nid
        myName = prefs.getString("name", "") ?: ""

        decoder.onSessionEnd = {
            sessionSender = -1
            runOnUiThread { onSessionEnd() }
        }

        buildUi()
        updateSpeedButton()
        updateCounter()

        // sender worker: one message at a time, in order
        lifecycleScope.launch(Dispatchers.Default) {
            for (msg in outbox) sendReliably(msg)
        }

        // status ticker
        lifecycleScope.launch {
            while (true) {
                refreshStatus()
                delay(200)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        resetDecoder = true
    }

    override fun onDestroy() {
        super.onDestroy()
        sosJob?.cancel()
        analysisExecutor.shutdown()
    }

    // =====================================================================================
    // UI
    // =====================================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Frosted-glass look: translucent white fill + thin light border, drawn over soft colour blobs. */
    private fun glass(radiusDp: Int, fill: Int = 0x1FFFFFFF, stroke: Int = 0x40FFFFFF): GradientDrawable {
        val g = GradientDrawable()
        g.setCornerRadius(dp(radiusDp).toFloat())
        g.setColor(fill)
        g.setStroke(dp(1), stroke)
        return g
    }

    private fun accentBg(radiusDp: Int): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xCC7B5CFF.toInt(), 0xCC00B8FF.toInt())
        )
        g.setCornerRadius(dp(radiusDp).toFloat())
        g.setStroke(dp(1), 0x66FFFFFF)
        return g
    }

    private fun blob(color: Int, sizeDp: Int): View {
        val v = View(this)
        val g = GradientDrawable()
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        g.setGradientRadius(dp(sizeDp) / 2f)
        g.setColors(intArrayOf(color, color and 0x00FFFFFF))
        v.background = g
        return v
    }

    private fun glassButton(label: String, bg: GradientDrawable? = null, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(12), dp(8), dp(12))
        t.background = bg ?: glass(16)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun buildUi() {
        val stack = FrameLayout(this)
        stack.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#140A33"), Color.parseColor("#0A2150"), Color.parseColor("#06304A"))
        )
        // soft colour blobs behind the glass panels
        val b1 = FrameLayout.LayoutParams(dp(380), dp(380), Gravity.TOP or Gravity.RIGHT)
        b1.topMargin = -dp(120); b1.rightMargin = -dp(130)
        stack.addView(blob(0x997B5CFF.toInt(), 380), b1)
        val b2 = FrameLayout.LayoutParams(dp(440), dp(440), Gravity.BOTTOM or Gravity.LEFT)
        b2.bottomMargin = -dp(170); b2.leftMargin = -dp(170)
        stack.addView(blob(0x7700D1FF.toInt(), 440), b2)
        val b3 = FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER_VERTICAL or Gravity.LEFT)
        b3.leftMargin = -dp(170)
        stack.addView(blob(0x66FF4FA3.toInt(), 300), b3)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(14), dp(12), dp(14), dp(12))
        stack.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- header: logo + title + settings ---
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        header.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(12), 0, dp(12), 0)
        titles.addView(label("LightChat", 21f, Color.WHITE, true), wrap())
        titles.addView(label("پیام‌رسان با نور", 12f, Color.parseColor("#B9C3FF")), wrap())
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(glassButton("⚙") { showSettingsDialog() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(header, matchWrap())

        // --- status card: camera preview + live status ---
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = glass(24)
        card.setPadding(dp(10), dp(10), dp(14), dp(10))
        previewView = PreviewView(this)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.background = glass(18, 0x33000000, 0x55FFFFFF)
        previewView.outlineProvider = ViewOutlineProvider.BACKGROUND
        previewView.clipToOutline = true
        card.addView(previewView, LinearLayout.LayoutParams(dp(92), dp(92)))
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(dp(12), 0, dp(12), 0)
        val lampRow = LinearLayout(this)
        lampRow.orientation = LinearLayout.HORIZONTAL
        lampRow.gravity = Gravity.CENTER_VERTICAL
        val lamp = View(this)
        lampDrawable = GradientDrawable()
        lampDrawable.setShape(GradientDrawable.OVAL)
        lampDrawable.setColor(Color.parseColor("#424242"))
        lamp.background = lampDrawable
        lampRow.addView(lamp, LinearLayout.LayoutParams(dp(14), dp(14)))
        statusText = label("", 13f, Color.WHITE)
        statusText.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("وضعیت دریافت (برای عیب‌یابی)")
                .setMessage(decoder.diagnostics())
                .setPositiveButton("باشه", null)
                .show()
        }
        statusText.setPadding(dp(10), 0, dp(10), 0)
        lampRow.addView(statusText, wrap())
        info.addView(lampRow, wrap())
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cardLp = matchWrap()
        cardLp.topMargin = dp(12)
        root.addView(card, cardLp)

        // --- first-run guide ---
        val guide = LinearLayout(this)
        guide.orientation = LinearLayout.VERTICAL
        guide.background = glass(22, 0x337B5CFF, 0x667B5CFF)
        guide.setPadding(dp(14), dp(12), dp(14), dp(12))
        guide.addView(label("راهنمای شروع", 15f, Color.WHITE, true), wrap())
        val steps = label(
            "۱) روی هر دو گوشی اپ را باز کنید و اجازه‌ی دوربین بدهید.\n" +
                "۲) گوشی‌ها را پشت‌به‌پشت و رو‌به‌روی هم، ۲۰ تا ۵۰ سانتی‌متر نگه دارید؛ چراغ هر گوشی باید در پیش‌نمایش گوشی مقابل دیده شود.\n" +
                "۳) پیام را بنویسید و «ارسال» را بزنید. ✓ یعنی رسید. متن همین‌طور که می‌رسد زنده نشان داده می‌شود.",
            12f, Color.parseColor("#E3E8FF")
        )
        steps.setPadding(0, dp(6), 0, dp(8))
        guide.addView(steps, matchWrap())
        guide.addView(glassButton("فهمیدم", accentBg(14)) {
            guideCard.visibility = View.GONE
            prefs.edit().putBoolean("guideSeen", true).apply()
        }, matchWrap())
        guideCard = guide
        if (prefs.getBoolean("guideSeen", false)) guide.visibility = View.GONE
        val guideLp = matchWrap()
        guideLp.topMargin = dp(10)
        root.addView(guide, guideLp)

        // --- chat ---
        scroll = ScrollView(this)
        chatContainer = LinearLayout(this)
        chatContainer.orientation = LinearLayout.VERTICAL
        emptyHint = label("هنوز پیامی نیست.\nیک پیام بنویسید یا از «پیام‌های آماده» استفاده کنید.", 13f, Color.parseColor("#8E9AD6"))
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(0, dp(40), 0, 0)
        chatContainer.addView(emptyHint, matchWrap())
        scroll.addView(chatContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scrollLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollLp.topMargin = dp(8)
        root.addView(scroll, scrollLp)

        // --- quick actions ---
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        fun chipLp(): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(3); lp.rightMargin = dp(3)
            return lp
        }
        chips.addView(glassButton("⚡ آماده") { showQuickDialog() }, chipLp())
        sosBtn = glassButton("🆘 SOS", glass(16, 0x44FF3B3B, 0x77FF6B6B)) { toggleSos() }
        chips.addView(sosBtn, chipLp())
        speedBtn = glassButton("") { cycleSpeed() }
        chips.addView(speedBtn, chipLp())
        val chipsLp = matchWrap()
        chipsLp.topMargin = dp(6)
        root.addView(chips, chipsLp)

        val chips2 = LinearLayout(this)
        chips2.orientation = LinearLayout.HORIZONTAL
        destBtn = glassButton("") { showDestDialog() }
        destBtn.text = destLabel()
        chips2.addView(destBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).also {
            it.leftMargin = dp(3); it.rightMargin = dp(3)
        })
        chips2.addView(glassButton("🧹 پاک") { clearChat() }, chipLp())
        val chips2Lp = matchWrap()
        chips2Lp.topMargin = dp(6)
        root.addView(chips2, chips2Lp)

        counter = label("", 11f, Color.parseColor("#9AA5D6"))
        counter.setPadding(dp(6), dp(6), dp(6), dp(2))
        root.addView(counter, matchWrap())

        // --- input bar ---
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = glass(28, 0x26FFFFFF, 0x55FFFFFF)
        bar.setPadding(dp(16), dp(4), dp(6), dp(4))
        input = EditText(this)
        input.hint = "پیام کوتاه…"
        input.background = null
        input.textSize = 16f
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.parseColor("#8E9AD6"))
        input.maxLines = 3
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCounter()
            }
        })
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val send = glassButton("ارسال", accentBg(22)) { onSendClicked() }
        send.setPadding(dp(18), dp(12), dp(18), dp(12))
        bar.addView(send, wrap())
        root.addView(bar, matchWrap())

        setContentView(stack)
    }

    private fun speedLabel(): String =
        if (speedMode == 0) "⏱ سرعت خودکار" else "⏱ ${FrameCodec.HALF_MS_CHOICES[speedMode - 1].toInt()}ms"

    private fun updateSpeedButton() {
        speedBtn.text = speedLabel()
    }

    private fun cycleSpeed() {
        speedMode = (speedMode + 1) % (FrameCodec.HALF_MS_CHOICES.size + 1)
        prefs.edit().putInt("speedMode", speedMode).apply()
        updateSpeedButton()
        updateCounter()
    }

    private fun chooseTxSpeed(): Int =
        if (speedMode == 0) minOf(autoIdx, peerMax).coerceIn(0, FrameCodec.HALF_MS_CHOICES.size - 1)
        else speedMode - 1

    private fun pickChunkSize(streamLen: Int): Int {
        var cs = chunkPref.coerceIn(2, Msg.MAX_CHUNK)
        while (Msg.chunkCount(streamLen, cs) > Msg.MAX_CHUNKS && cs < Msg.MAX_CHUNK) cs++
        return cs
    }

    private fun estimateSeconds(streamLen: Int, speedIdx: Int): Int {
        val cs = pickChunkSize(streamLen)
        val chunks = Msg.chunkCount(streamLen, cs)
        val wireBytes = streamLen + chunks * 4 // header + meta + crc16 per chunk
        return Math.round(FrameCodec.estimateSlots(wireBytes, chunks) * FrameCodec.HALF_MS_CHOICES[speedIdx] / 1000.0).toInt()
    }

    private fun updateCounter() {
        val n = 1 + TextCodec.encode(input.text.toString().trim()).size + (if (key != null) 2 else 0)
        val maxB = Msg.maxStreamBytes(Msg.MAX_CHUNK)
        var text = "$n / $maxB بایت"
        if (n > 0) text += "  •  حدود ${estimateSeconds(n, chooseTxSpeed())} ثانیه"
        if (key != null) text += "  •  🔒 رمزنگاری روشن"
        counter.text = text
        counter.setTextColor(if (n > maxB) Color.parseColor("#FF6E6E") else Color.parseColor("#9AA5B1"))
    }

    private fun qualityBars(): String {
        val c = decoder.contrast
        var q = when {
            c >= 120f -> 4
            c >= 80f -> 3
            c >= 50f -> 2
            c >= LightDecoder.MIN_CONTRAST -> 1
            else -> 0
        }
        if (decoder.receiving) {
            if (decoder.jitter > 0.25) q = minOf(q, 1) else if (decoder.jitter > 0.15) q = minOf(q, 2)
        }
        return "[" + "|".repeat(q) + ".".repeat(4 - q) + "]"
    }

    private fun refreshStatus() {
        lampDrawable.setColor(if (decoder.signalOn) Color.parseColor("#FFEB3B") else Color.parseColor("#424242"))
        val state = when {
            camera == null -> "دوربین آماده نیست"
            txActive -> "در حال ارسال…"
            decoder.receiving -> "در حال دریافت…"
            else -> "آماده (گوش می‌دهد)"
        }
        val fps = decoder.fps
        val maxIdx = FrameCodec.maxSpeedIndex(fps)
        statusText.text = "$state\nسیگنال ${qualityBars()}  •  ${fps.toInt()}fps  •  اعضا: ${activeMembers().size + 1}\n" +
            "حداکثر سرعت دریافت: ${FrameCodec.HALF_MS_CHOICES[maxIdx].toInt()}ms"
    }

    private fun addBubble(msg: String, mine: Boolean, status: String?): Bubble {
        emptyHint.visibility = View.GONE
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        tv.background = if (mine) accentBg(20) else glass(20, 0x26FFFFFF, 0x44FFFFFF)
        tv.text = if (status != null) "$msg  $status" else msg
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.RIGHT else Gravity.LEFT
        lp.topMargin = dp(4)
        lp.bottomMargin = dp(4)
        chatContainer.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return Bubble(tv, msg)
    }

    private fun setBubbleStatus(b: Bubble, status: String) {
        runOnUiThread { b.view.text = "${b.text}  $status" }
    }

    private fun clearChat() {
        chatContainer.removeAllViews()
        chatContainer.addView(emptyHint, matchWrap())
        emptyHint.visibility = View.VISIBLE
        bubbles.clear()
        pendingAck.clear()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // =====================================================================================
    // dialogs
    // =====================================================================================

    // =====================================================================================
    // group: identity and members
    // =====================================================================================

    private fun displayName(): String = if (myName.isBlank()) "کاربر $myId" else myName.trim()

    private fun nameOf(id: Int): String {
        val n = members[id]?.name
        return if (n.isNullOrBlank()) "کاربر $id" else n
    }

    private fun touchMember(id: Int, name: String?, uid: Int?) {
        if (id == myId || id !in 1..Msg.MAX_NODE) return
        val m = members.getOrPut(id) { Member("", -1, 0L) }
        m.lastHeard = SystemClock.elapsedRealtime()
        if (!name.isNullOrBlank()) m.name = name
        if (uid != null) m.uid = uid
    }

    /** Phones heard recently (other than me), sorted by id. */
    private fun activeMembers(): List<Int> {
        val now = SystemClock.elapsedRealtime()
        return members.filter { now - it.value.lastHeard < MEMBER_TTL_MS }.keys.sorted()
    }

    /** Two phones picked the same id: the one with the larger random uid takes another id. */
    private fun handleHello(h: Msg.Hello) {
        touchMember(h.id, h.name, h.uid)
        if (h.id == myId && h.uid != myUid && myUid > h.uid) {
            val free = (1..Msg.MAX_NODE).filter { it != myId && !members.containsKey(it) }
            if (free.isNotEmpty()) {
                myId = free.random()
                prefs.edit().putInt("nodeId", myId).apply()
                helloDue = true
                toast("شناسه‌ی شما برای جلوگیری از تداخل عوض شد")
            }
        }
    }

    private fun destLabel(): String = if (destId == Msg.ALL) "👥 به: همه" else "👤 به: ${nameOf(destId)}"

    private fun showDestDialog() {
        val ids = activeMembers()
        val labels = ArrayList<String>()
        labels.add("همه‌ی اعضای گروه")
        for (id in ids) labels.add(nameOf(id))
        val title = if (ids.isEmpty()) "گیرنده (هنوز کسی شناخته نشده)" else "گیرنده‌ی پیام"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i ->
                destId = if (i == 0) Msg.ALL else ids[i - 1]
                destBtn.text = destLabel()
            }
            .show()
    }

    private fun applyPassphrase(p: String) {
        key = if (p.isBlank()) null else Crypt.deriveKey(p)
    }

    private fun showQuickDialog() {
        AlertDialog.Builder(this)
            .setTitle("پیام‌های آماده")
            .setItems(QUICK) { _, i -> sendText(QUICK[i]) }
            .show()
    }

    private fun showSettingsDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), dp(0))

        val nameField = EditText(this)
        nameField.hint = "نام شما در گروه (اختیاری)"
        nameField.setText(myName)
        box.addView(nameField, matchWrap())

        val pass = EditText(this)
        pass.hint = "رمز مشترک (اختیاری؛ خالی = بدون رمز)"
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        pass.setText(prefs.getString("pass", "") ?: "")
        box.addView(pass, matchWrap())
        val note = TextView(this)
        note.textSize = 11f
        note.text = "رمز می‌تواند شامل حروف فارسی یا انگلیسی، عدد و علامت باشد. هر دو گوشی باید دقیقاً یک رمز داشته باشند. " +
            "رمز فقط محتوای پیام را از کسی که چشمک را می‌بیند پنهان می‌کند. اگر رمز اشتباه باشد متن بی‌معنی می‌آید."
        box.addView(note, matchWrap())

        val vib = CheckBox(this)
        vib.text = "لرزش هنگام دریافت پیام"
        vib.isChecked = vibrateOn
        box.addView(vib, matchWrap())

        val fast = CheckBox(this)
        fast.text = "درخواست ۶۰ فریم بر ثانیه از دوربین (اگر گوشی پشتیبانی کند، سرعت دو برابر می‌شود)"
        fast.isChecked = wantHighFps
        box.addView(fast, matchWrap())

        val guide = CheckBox(this)
        guide.text = "نمایش دوباره‌ی راهنمای شروع"
        guide.isChecked = false
        box.addView(guide, matchWrap())

        AlertDialog.Builder(this)
            .setTitle("تنظیمات")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val p = pass.text.toString()
                val nm = nameField.text.toString().trim()
                if (nm != myName) helloDue = true
                myName = nm
                prefs.edit().putString("name", nm).putString("pass", p).putBoolean("vibrate", vib.isChecked)
                    .putBoolean("highfps", fast.isChecked).apply()
                applyPassphrase(p)
                updateCounter()
                vibrateOn = vib.isChecked
                val fastChanged = fast.isChecked != wantHighFps
                wantHighFps = fast.isChecked
                if (guide.isChecked) guideCard.visibility = View.VISIBLE
                if (fastChanged) startCamera()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    // =====================================================================================
    // camera (receiver side + torch control)
    // =====================================================================================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                bindCamera(provider, null)
                if (wantHighFps) {
                    val range = pickFastRange()
                    if (range != null) bindCamera(provider, range)
                }
            } catch (t: Throwable) {
                Toast.makeText(this, "خطا در راه‌اندازی دوربین: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("UnsafeOptInUsageError")
    private fun bindCamera(provider: ProcessCameraProvider, fpsRange: Range<Int>?) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val ab = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (fpsRange != null) {
            try {
                Camera2Interop.Extender(ab)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            } catch (t: Throwable) {
                // device refuses: stay with the default frame rate
            }
        }
        val analysis = ab.build()
        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

        provider.unbindAll()
        val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

        // Darker exposure makes the other phone's torch stand out from the background.
        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(exposure.exposureCompensationRange.lower)
        }
        camera = cam
        resetDecoder = true
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "این گوشی چراغ قوه ندارد؛ فقط دریافت ممکن است", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("UnsafeOptInUsageError")
    private fun pickFastRange(): Range<Int>? {
        val cam = camera ?: return null
        return try {
            val ranges = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: return null
            var best: Range<Int>? = null
            for (r in ranges) {
                if (r.upper >= 60 && (best == null || r.lower > best.lower)) best = r
            }
            best
        } catch (t: Throwable) {
            null
        }
    }

    /** Runs on [analysisExecutor] for every camera frame. */
    private fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (txActive || now < ignoreUntil) {
                resetDecoder = true // our own light must never be decoded
                return
            }
            if (resetDecoder) {
                decoder.reset()
                resetDecoder = false
            }
            val tMs = image.imageInfo.timestamp / 1_000_000.0
            decoder.feed(tMs, measureBrightness(image))
        } finally {
            image.close()
        }
    }

    /**
     * Brightness of the "light source" in the frame: the mean of the brightest 2% of the
     * (sub-sampled) luma pixels. A phone torch pointed at the camera dominates this value.
     */
    private fun measureBrightness(image: ImageProxy): Float {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val limit = buf.limit()
        val hist = IntArray(256)
        var n = 0
        var y = 0
        while (y < h) {
            val rowStart = y * rowStride
            var x = 0
            while (x < w) {
                val idx = rowStart + x * pixStride
                if (idx < limit) {
                    val v = buf.get(idx).toInt() and 0xFF
                    hist[v] = hist[v] + 1
                    n++
                }
                x += 4
            }
            y += 4
        }
        if (n == 0) return 0f
        val want = maxOf(1, n / 50)
        var taken = 0
        var sum = 0L
        var level = 255
        while (level >= 0 && taken < want) {
            val c = minOf(hist[level], want - taken)
            sum += c.toLong() * level
            taken += c
            level--
        }
        return sum.toFloat() / taken
    }

    private fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    // =====================================================================================
    // transmit
    // =====================================================================================

    /** Waits until nobody else seems to be sending, plus a small random delay. */
    private suspend fun waitForIdleChannel() {
        var waited = 0L
        while (decoder.receiving && waited < 150_000L) {
            delay(200)
            waited += 200
        }
        delay(100L + (Math.random() * 400).toLong())
    }

    /** Blinks one complete transmission (all given wire frames back to back). */
    private suspend fun transmit(wires: List<ByteArray>, speedIdx: Int) {
        txMutex.withLock {
            waitForIdleChannel()
            txActive = true
            val halfMs = FrameCodec.HALF_MS_CHOICES[speedIdx.coerceIn(0, FrameCodec.HALF_MS_CHOICES.size - 1)].toLong()
            try {
                val halves = FrameCodec.toSlots(wires)
                setTorch(false)
                delay(400) // quiet time so the other side's threshold settles
                val t0 = SystemClock.elapsedRealtime()
                var cur = false
                for (i in halves.indices) {
                    val wait = t0 + i * halfMs - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    if (halves[i] != cur) {
                        cur = halves[i]
                        setTorch(cur)
                    }
                }
                val tail = t0 + halves.size * halfMs - SystemClock.elapsedRealtime()
                if (tail > 0) delay(tail)
            } finally {
                setTorch(false)
                ignoreUntil = SystemClock.elapsedRealtime() + TX_GUARD_MS
                txActive = false
            }
        }
    }

    private fun onSendClicked() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (sendText(text)) input.setText("")
    }

    /** Queues a text message. Returns false (with a toast) when it cannot be sent. */
    private fun sendText(text: String): Boolean {
        val n = 1 + TextCodec.encode(text).size + (if (key != null) 2 else 0)
        if (n == 1) return false
        if (n > Msg.maxStreamBytes(Msg.MAX_CHUNK)) {
            toast("پیام بلندتر از ${Msg.maxStreamBytes(Msg.MAX_CHUNK)} بایت است")
            return false
        }
        val cam = camera
        if (cam == null) {
            toast("دوربین هنوز آماده نیست")
            return false
        }
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("این گوشی چراغ قوه ندارد")
            return false
        }
        val bubble = addBubble(if (destId == Msg.ALL) text else "➜ ${nameOf(destId)}: $text", true, "⏳")
        outbox.trySend(OutMessage(text, bubble, destId))
        return true
    }

    private suspend fun sendReliably(msg: OutMessage) {
        msgMutex.withLock { deliver(msg) }
    }

    /** Time one ACK transmission takes (7 wire bytes) at this speed, plus a guard. */
    private fun ackSlotMs(halfMs: Double): Long = (FrameCodec.halfSymbolCount(7) * halfMs).toLong() + 1500L

    private fun ackWindowMs(speedIdx: Int, expectedCount: Int): Long {
        val half = FrameCodec.HALF_MS_CHOICES[speedIdx.coerceIn(0, FrameCodec.HALF_MS_CHOICES.size - 1)]
        return 6000L + maxOf(1, expectedCount) * ackSlotMs(half)
    }

    /** Selective-repeat delivery of one message to one phone or to the whole group. */
    private suspend fun deliver(msg: OutMessage) {
        val id = nextMsgId
        nextMsgId = (nextMsgId + 1) and 3

        val textBytes = TextCodec.encode(msg.text)
        val k = key
        val header = Msg.streamHeader(myId, msg.dest)
        val stream: ByteArray
        if (k != null) { // optional passphrase encryption: [header][nonce 2 bytes][ciphertext]
            val nonce = (Math.random() * 65536).toInt() and 0xFFFF
            val c = Crypt.apply(k, nonce, 0, textBytes)
            stream = ByteArray(3 + c.size)
            stream[0] = header.toByte()
            stream[1] = (nonce ushr 8).toByte()
            stream[2] = nonce.toByte()
            System.arraycopy(c, 0, stream, 3, c.size)
        } else {
            stream = ByteArray(1 + textBytes.size)
            stream[0] = header.toByte()
            System.arraycopy(textBytes, 0, stream, 1, textBytes.size)
        }
        val frames = Msg.buildChunks(id, stream, k != null, pickChunkSize(stream.size))
        val wires = frames.map { FrameCodec.encode(it) }
        val full = Msg.fullMask(frames.size)

        val expected: List<Int> = if (msg.dest != Msg.ALL) listOf(msg.dest) else activeMembers()
        val tracker = AckTracker(full, expected)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS && !tracker.delivered()) {
            attempts++
            var idx = chooseTxSpeed()
            if (attempts >= 3) idx = maxOf(0, idx - (attempts - 2)) // slow down when it keeps failing
            val missing = tracker.missingMask()
            val toSend = ArrayList<ByteArray>()
            if (helloDue) toSend.add(FrameCodec.encode(Msg.buildHello(myId, myUid, displayName())))
            for (i in wires.indices) if ((missing and (1 shl i)) != 0) toSend.add(wires[i])

            setBubbleStatus(msg.bubble, "⏳ ($attempts/$MAX_ATTEMPTS)")
            while (ackChannel.tryReceive().isSuccess) {
                // drop stale ACKs
            }
            transmit(toSend, idx)
            if (helloDue) {
                helloDue = false
                sentSinceHello = 0
            }

            withTimeoutOrNull(ackWindowMs(idx, expected.size)) {
                var done = false
                while (!done) {
                    val a = ackChannel.receive()
                    if (a.msgId == id && a.target == myId) {
                        tracker.onAck(a.acker, a.mask)
                        runOnUiThread { touchMember(a.acker, null, null) }
                        peerMax = a.maxSpeed.coerceIn(0, FrameCodec.HALF_MS_CHOICES.size - 1)
                        done = tracker.allExpectedAnswered || (expected.isEmpty() && tracker.anyAck)
                    }
                }
                true
            }
            if (!tracker.anyAck) delay(500L + (Math.random() * 2000).toLong())
        }

        sentSinceHello++
        if (sentSinceHello >= HELLO_EVERY) helloDue = true

        val delivered = tracker.delivered()
        if (delivered && attempts == 1) {
            successStreak++
            if (successStreak >= 2) {
                autoIdx = minOf(autoIdx + 1, peerMax)
                successStreak = 0
            }
            chunkPref = minOf(Msg.DEFAULT_CHUNK, chunkPref + 1)
        } else {
            successStreak = 0
            autoIdx = maxOf(0, autoIdx - 1)
            chunkPref = maxOf(4, chunkPref - 2)
        }
        val got = tracker.ackedBy().size
        val status = when {
            delivered && expected.size > 1 -> "✓ ($got/${expected.size})"
            delivered -> "✓"
            got > 0 && expected.size > 1 -> "◐ ($got/${expected.size})"
            else -> "✗"
        }
        setBubbleStatus(msg.bubble, status)
        runOnUiThread { updateCounter() }
    }

    // =====================================================================================
    // SOS
    // =====================================================================================

    private fun toggleSos() {
        val job = sosJob
        if (job != null && job.isActive) {
            job.cancel()
            sosBtn.text = "🆘 SOS"
            return
        }
        if (camera == null || camera?.cameraInfo?.hasFlashUnit() != true) {
            toast("چراغ قوه در دسترس نیست")
            return
        }
        sosBtn.text = "⛔ توقف SOS"
        val bubble = addBubble(QUICK[0], true, "SOS…")
        sosJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                morseSos() // for human eyes
                delay(1500)
                sendReliably(OutMessage(QUICK[0], bubble, Msg.ALL)) // for the other phones
                delay(5000)
            }
        }
    }

    /** International Morse "SOS" (... --- ...) with the torch, 250 ms unit. */
    private suspend fun morseSos() {
        txMutex.withLock {
            txActive = true
            try {
                val unit = 250L
                val letters = listOf(listOf(1, 1, 1), listOf(3, 3, 3), listOf(1, 1, 1))
                for (letter in letters) {
                    for ((i, d) in letter.withIndex()) {
                        setTorch(true)
                        delay(d * unit)
                        setTorch(false)
                        delay(if (i < letter.size - 1) unit else 3 * unit)
                    }
                }
                delay(4 * unit)
            } finally {
                setTorch(false)
                ignoreUntil = SystemClock.elapsedRealtime() + TX_GUARD_MS
                txActive = false
            }
        }
    }

    // =====================================================================================
    // receive
    // =====================================================================================

    /** Called on the analyzer thread for every CRC-valid frame, as soon as it ends. */
    private fun onFrameDecoded(frame: Frame) {
        when (frame.type) {
            FrameCodec.TYPE_DATA -> {
                val c = Msg.parseChunk(frame) ?: return
                if (c.index == 0 && c.data.isNotEmpty()) sessionSender = (c.data[0].toInt() ushr 4) and 15
                val hint = sessionSender
                runOnUiThread { handleChunk(c, hint) }
            }
            FrameCodec.TYPE_ACK -> {
                val a = Msg.parseAck(frame) ?: return
                ackChannel.trySend(a)
                runOnUiThread { touchMember(a.acker, null, null) }
            }
            FrameCodec.TYPE_CTRL -> {
                val h = Msg.parseHello(frame) ?: return
                runOnUiThread { handleHello(h) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(ms: Long) {
        if (!vibrateOn) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (t: Throwable) {
            // no vibrator
        }
    }

    /** UI thread: a chunk arrived. Show the text so far immediately (live). */
    private fun handleChunk(c: Msg.Chunk, hintSender: Int) {
        val r = assembler.onChunk(c, SystemClock.elapsedRealtime(), hintSender)
        val m = r.message
        if (m.sender < 1 || m.sender == myId) return // header not seen yet, or an echo of ourselves
        if (m.dest != Msg.ALL && m.dest != myId) return // private message for somebody else
        touchMember(m.sender, null, null)
        if (!pendingAck.contains(m)) pendingAck.add(m)

        var b = bubbles[m]
        if (b == null) {
            b = addBubble("", false, null)
            bubbles[m] = b
            buzz(80)
        }
        val k = key
        val body = if (m.encrypted && k == null) "🔒 پیام رمزنگاری‌شده (رمز را در ⚙ تنظیمات وارد کنید)" else m.text(k)
        val done = m.complete
        val who = (if (m.dest != Msg.ALL) "🔒 خصوصی · " else "") + "👤 " + nameOf(m.sender)
        b.view.text = who + "\n" + (if (done) body else "$body …")
        val bg = b.view.background
        if (bg is GradientDrawable) {
            bg.setColor(if (body.startsWith("SOS")) 0xCCD32F2F.toInt() else 0x26FFFFFF)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (done && !m.shownComplete) {
            m.shownComplete = true
            buzz(250)
        }
    }

    private fun nearestSpeed(halfMs: Double): Int {
        if (halfMs <= 0.0) return 1
        var best = 0
        var bestErr = Double.MAX_VALUE
        for (i in FrameCodec.HALF_MS_CHOICES.indices) {
            val e = Math.abs(FrameCodec.HALF_MS_CHOICES[i] - halfMs)
            if (e < bestErr) {
                bestErr = e
                best = i
            }
        }
        return best
    }

    /**
     * UI thread: a transmission ended. Answer with an ACK mask for each message that was in it. In a group
     * every receiver waits for its own time slot (by rank among the known members) so ACKs don't collide.
     */
    private fun onSessionEnd() {
        val job = ackJob
        if (pendingAck.isEmpty() || (job != null && job.isActive)) return
        val list = ArrayList(pendingAck)
        pendingAck.clear()
        val half = decoder.lastHalfMs
        ackJob = lifecycleScope.launch(Dispatchers.Default) {
            for (m in list) {
                val others = (activeMembers() + myId).filter { it != m.sender }.distinct().sorted()
                val rank = maxOf(0, others.indexOf(myId))
                delay(ACK_DELAY_MS + rank * ackSlotMs(if (half > 0) half else 140.0))
                while (decoder.receiving) delay(200) // somebody is already sending again
                val ack = Msg.buildAck(m.msgId, FrameCodec.maxSpeedIndex(decoder.fps), m.mask, m.sender, myId)
                transmit(listOf(FrameCodec.encode(ack)), nearestSpeed(half))
            }
            runOnUiThread { if (pendingAck.isNotEmpty()) onSessionEnd() }
        }
    }
}
