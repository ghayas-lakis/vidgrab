package com.example.vidgrab

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private val C_TOP = Color.parseColor("#071A5C")
private val C_BOTTOM = Color.parseColor("#0E45C8")
private val C_PRIMARY = Color.parseColor("#1E6BFF")
private val C_SOFT = Color.parseColor("#E4ECFF")
private val C_DANGER = Color.parseColor("#E5484D")
private val C_TEXT = Color.parseColor("#14213D")
private val C_MUTED = Color.parseColor("#6B7A99")
private val C_FIELD = Color.parseColor("#F0F4FF")
private val C_SUB = Color.parseColor("#BFD2FF")
private val C_OK = Color.parseColor("#1F9D55")
private val C_WARN = Color.parseColor("#B86E00")
private val C_GLASS = Color.argb(38, 255, 255, 255)
private const val PRIMARY = 0
private const val SOFT = 1
private const val DANGER = 2
// Marks our own progress lines (see --progress-template in buildRequest).
private const val PROG = "VGP|"
// Start of optimize()'s answer for audio and other non-video files (expected, not an error).
private const val NOT_VIDEO = "Not a video file"

class MainActivity : Activity() {
    // choices: height in pixels, 0 = best available, -1 = audio only (MP3)
    // thumb: thumbnail URL; meta: "Site · 12:34 · Uploader"
    class Item(val url: String, val title: String, val labels: List<String>, val choices: List<Int>,
               val thumb: String = "", val meta: String = "") {
        lateinit var check: CheckBox
        lateinit var thumbView: ImageView
        lateinit var spinner: Spinner
        lateinit var bar: ProgressBar
        lateinit var state: TextView
        lateinit var box: LinearLayout
        lateinit var toggle: Button
        lateinit var stopBtn: Button
        @Volatile var busy = false
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var pid = ""
        @Volatile var proc = false        // a yt-dlp process is running for this item
        @Volatile var post = false        // merging / converting: needs no network, never interrupted
        @Volatile var netKill = false     // stopped on purpose because the network changed: always resumed
        @Volatile var afterNet = false    // resumed after an interruption: its speed is being watched
        @Volatile var runDir: File? = null
        @Volatile var pct = -1            // last known progress, kept across interruptions
        @Volatile var runOnWifi = false
        @Volatile var runStart = 0L
        @Volatile var runBytes0 = -1.0
        @Volatile var lastBytes = -1.0
        @Volatile var lastUi = 0L
        @Volatile var peak = 0.0          // best speed seen before an interruption (bytes/s)
        @Volatile var peakOnWifi = false
        @Volatile var slowRestarts = 0
        @Volatile var err = ""            // full text of the last failure, shown when the status is tapped
        var done = false
        val files = mutableListOf<String>()
    }

    private lateinit var input: EditText
    private lateinit var folderBtn: Button
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var dlBtn: Button
    private lateinit var selectAll: CheckBox
    private lateinit var countLabel: TextView
    private lateinit var empty: TextView
    private lateinit var prefs: SharedPreferences
    @Volatile private var updating = false
    @Volatile private var checkErr = ""                           // details of the last failed link check
    private var syncing = false                                   // updating "Select all" from code
    private val imgPool = Executors.newFixedThreadPool(2)         // thumbnails
    private val analyzer = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newFixedThreadPool(2)
    private val items = CopyOnWriteArrayList<Item>()
    private var dp = 1f
    private var svcOn = false
    @Volatile private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkRun = Runnable { autoCheck() }
    private val checking = ConcurrentHashMap.newKeySet<String>()   // links being checked right now
    private var prevLinks = setOf<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()     // links that cannot be downloaded (not retried)

    companion object {
        // Downloads running in this process; survives the activity being recreated.
        private val running = ConcurrentHashMap.newKeySet<String>()
    }

    @Volatile private var curNet: Network? = null

    // When the connection drops or changes, stop running downloads at once so they never keep using a
    // dead connection; the work loop resumes them from the partial file on a usable network.
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prev = curNet
            curNet = network
            if (prev != null && prev != network) restartRunning()   // e.g. mobile data -> Wi-Fi
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (wifiOnly() && !onWifi(getSystemService(ConnectivityManager::class.java), caps)) restartRunning()
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) runOnUiThread { autoCheck() }
        }

        override fun onLost(network: Network) {
            if (network == curNet) curNet = null
            restartRunning()
        }
    }

    private fun kill(a: Item) {
        try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
    }

    /** Stops every download that is transferring data; each one resumes by itself (from its partial file). */
    private fun restartRunning() {
        for (a in items) if (a.proc && !a.post) {
            a.netKill = true
            a.afterNet = true
            a.slowRestarts = 0
            kill(a)
        }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun bg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(r).toFloat()
    }

    private fun darker(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] *= 0.85f
        return Color.HSVToColor(hsv)
    }

    /** Colors a button as PRIMARY (filled blue), SOFT (light blue) or DANGER (red). */
    private fun style(b: Button, kind: Int) {
        val fill = when (kind) { PRIMARY -> C_PRIMARY; DANGER -> C_DANGER; else -> C_SOFT }
        val fg = if (kind == SOFT) C_PRIMARY else Color.WHITE
        b.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), bg(Color.parseColor("#DDE3F0"), 12))
            addState(intArrayOf(android.R.attr.state_pressed), bg(darker(fill), 12))
            addState(intArrayOf(), bg(fill, 12))
        }
        b.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.parseColor("#8C96AB"), fg)))
    }

    private fun btn(text: String, kind: Int = SOFT, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.stateListAnimator = null
        b.minHeight = px(44)
        b.minimumHeight = px(44)
        style(b, kind)
        b.setOnClickListener { onClick() }
        return b
    }

    /** Small light heading above a block on the blue background. */
    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        letterSpacing = 0.06f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(C_SUB)
        setPadding(px(6), px(12), px(6), 0)
    }

    /** A settings row: bold title, muted explanation, switch on the right. */
    private fun toggleRow(title: String, sub: String, on: Boolean, changed: (Boolean) -> Unit) =
        Switch(this).apply {
            val x = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            val sb = android.text.SpannableStringBuilder(title)
            sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, x)
            sb.append("\n")
            val st = sb.length
            sb.append(sub)
            sb.setSpan(android.text.style.ForegroundColorSpan(C_MUTED), st, sb.length, x)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), st, sb.length, x)
            text = sb
            textSize = 15f
            setTextColor(C_TEXT)
            setPadding(0, px(6), 0, px(6))
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C_PRIMARY, Color.parseColor("#F4F6FB")))
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#9DBBFF"), Color.parseColor("#C5CCDB")))
            isChecked = on
            setOnCheckedChangeListener { _, c -> changed(c) }
        }

    /** A thicker, rounded progress bar. */
    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        val front = android.graphics.drawable.ClipDrawable(bg(C_PRIMARY, 4), Gravity.START,
            android.graphics.drawable.ClipDrawable.HORIZONTAL)
        progressDrawable = android.graphics.drawable.LayerDrawable(
            arrayOf<android.graphics.drawable.Drawable>(bg(C_SOFT, 4), front)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun row(vararg views: View, weights: FloatArray? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((k, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
            weights?.getOrNull(k) ?: 1f).apply { setMargins(px(3), 0, px(3), 0) })
    }

    private fun add(parent: LinearLayout, v: View, m: Int = 4) = parent.addView(v,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, px(m), 0, px(m)) })

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.WHITE, 18)
        setPadding(px(14), px(10), px(14), px(10))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        dp = resources.displayMetrics.density
        prefs = getSharedPreferences("vidgrab", MODE_PRIVATE)
        window.statusBarColor = C_TOP
        window.navigationBarColor = C_TOP
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(18), px(14), px(16))
        }

        // header with the app logo
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.app_logo) },
            LinearLayout.LayoutParams(px(52), px(52)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "VidGrab"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(TextView(this).apply { text = "Download videos and music from the web"; textSize = 13f; setTextColor(C_SUB) })
        head.addView(titles)
        add(root, head)

        // status bubble (right under the header so it is always visible)
        status = TextView(this).apply {
            text = "Starting engine..."
            textSize = 14f
            setTextColor(Color.WHITE)
            background = bg(C_GLASS, 12)
            setPadding(px(12), px(9), px(12), px(9))
            setOnClickListener { if (checkErr.isNotEmpty()) showDetails("Details", checkErr) }
        }
        add(root, status, 8)

        // links card
        add(root, section("ADD LINKS"), 0)
        val links = card()
        input = EditText(this).apply {
            hint = "Paste links here, one per line.\nThey are checked automatically."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            background = bg(C_FIELD, 12)
            setPadding(px(12), px(10), px(12), px(10))
            setTextColor(C_TEXT)
            setHintTextColor(C_MUTED)
        }
        add(links, input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                handler.removeCallbacks(checkRun)
                handler.postDelayed(checkRun, 600)
            }
        })
        add(links, row(btn("Paste from clipboard", PRIMARY) { pasteClipboard() }, btn("Clear") { input.setText("") },
            weights = floatArrayOf(2f, 1f)))
        add(root, links, 6)

        // settings card
        add(root, section("SETTINGS"), 0)
        val settings = card()
        folderBtn = btn("") { pickFolder() }
        add(settings, folderBtn)
        updateFolderLabel()
        add(settings, toggleRow("Wi-Fi only", "Turn off to also download on mobile data", wifiOnly()) { c ->
            prefs.edit().putBoolean("wifiOnly", c).apply()
            // Turned on while on mobile data: pause running downloads until Wi-Fi is back.
            // Turned off: waiting downloads notice within a couple of seconds and continue.
            if (c && !canDownload()) restartRunning()
        }, 2)
        add(settings, toggleRow("Optimize after download", "Smoother playback and seeking. Turn off to optimize later",
            prefs.getBoolean("optimize", true)) { c -> prefs.edit().putBoolean("optimize", c).apply() }, 2)
        add(root, settings, 6)

        // video list: heading with count + "Select all", empty-state hint, then one card per video
        val listHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countLabel = section("YOUR VIDEOS")
        listHead.addView(countLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selectAll = CheckBox(this).apply {
            text = "Select all"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(0, px(12), 0, 0)
            setOnCheckedChangeListener { _, c -> if (!syncing) items.forEach { it.check.isChecked = c } }
        }
        listHead.addView(selectAll)
        add(root, listHead, 0)
        empty = TextView(this).apply {
            text = "No videos yet.\nPaste a link above, or share one to VidGrab from any app."
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(C_SUB)
            background = bg(C_GLASS, 18)
            setPadding(px(16), px(22), px(16), px(22))
        }
        add(root, empty, 6)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, list, 0)

        // bottom bar: the main action is always in reach; everything else is under "More"
        dlBtn = btn("Download", PRIMARY) { downloadSelected() }
        val more = btn("More") {}
        more.setOnClickListener { showMenu(more) }
        val bottom = row(dlBtn, more, weights = floatArrayOf(2f, 1f)).apply {
            setBackgroundColor(C_TOP)
            setPadding(px(11), px(10), px(11), px(10))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_TOP, C_BOTTOM))
            addView(root)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        updateCounts()
        restore()
        sweepPartials()
        handleIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                autoUpdate()
                ready = true
                runOnUiThread {
                    status.text ="Ready (engine ${YoutubeDL.getInstance().versionName(application)}). Paste links above."
                    autoCheck()
                }
            } catch (e: Exception) {
                checkErr = rawError(e)
                runOnUiThread { status.text = "Could not start the download engine. Tap here for details." }
            }
        }.start()
    }

    /**
     * Sites change often and an outdated engine stops working with them (the cause of "everything fails"
     * after a reinstall), so check for a new yt-dlp once a day at startup. It is a small download (~3 MB).
     */
    private fun autoUpdate() {
        val last = prefs.getLong("engineChecked", 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L || !online()) return
        updating = true
        runOnUiThread { status.text = "Updating the download engine..." }
        try {
            YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs.edit().putLong("engineChecked", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            // keep the current engine; it is tried again on the next start
        } finally {
            updating = false
        }
    }

    override fun onPause() { super.onPause(); persist() }

    // Back keeps the app (and its downloads) alive in the background instead of closing it.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (items.any { it.busy }) moveTaskToBack(true) else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) } catch (_: Exception) {}
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        handleIntent(i)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            input.append((Regex("https?://\\S+").find(t)?.value ?: t) + "\n")
        }
    }

    // ---------- connection + readable errors ----------
    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun wifiOnly() = prefs.getBoolean("wifiOnly", true)

    /** True for Wi-Fi / Ethernet, including a VPN running on top of Wi-Fi. */
    private fun onWifi(cm: ConnectivityManager, c: NetworkCapabilities): Boolean {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        if (!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        // A VPN hides the real connection: look at the networks under it.
        @Suppress("DEPRECATION") val nets = cm.allNetworks
        return nets.any { n ->
            val nc = cm.getNetworkCapabilities(n)
            nc != null && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun onWifiNow(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return onWifi(cm, c)
    }

    /** Online, and on Wi-Fi unless the user allowed mobile data. */
    private fun canDownload() = online() && (!wifiOnly() || onWifiNow())

    /** The full error text (yt-dlp's stderr and any causes), for the details dialog. */
    private fun rawError(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        while (c != null) { sb.append(c.message ?: c.javaClass.simpleName).append('\n'); c = c.cause }
        return sb.toString().trim()
    }

    /** Shows an error in full, with the engine version, and lets the user copy it. */
    private fun showDetails(title: String, text: String) {
        val engine = try { YoutubeDL.getInstance().versionName(application) } catch (_: Exception) { null }
        val full = ("Engine: " + (engine ?: "unknown") + "\n\n" + text).take(6000)
        AlertDialog.Builder(this).setTitle(title).setMessage(full)
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("VidGrab error", full))
                status.text = "Error details copied."
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Returns a short readable cause, and whether it is a connection problem (worth retrying). */
    private fun why(e: Throwable): Pair<String, Boolean> {
        if (!online()) return "No internet connection" to true
        val raw = rawError(e)
        val t = raw.lowercase()
        fun has(vararg k: String) = k.any { it in t }
        return when {
            has("private video") -> "This video is private" to false
            has("sign in", "log in", "login", "not a bot") -> "This site asks for a login" to false
            has("not available in your country", "geo restrict", "geo-restrict") -> "Not available in your country" to false
            has("unsupported url") -> "This link is not supported" to false
            has("video unavailable", "has been removed", "does not exist", "http error 404") -> "Video not found or removed" to false
            has("http error 403") -> "The site refused access (403)" to false
            has("http error 429", "too many requests") -> "Too many requests - try again later" to false
            has("no space left") -> "Phone storage is full" to false
            has("securityexception", "permission denied", "cannot create file") -> "Cannot write to the chosen folder - pick it again" to false
            has("timed out", "timeout") -> "Connection timed out" to true
            has("name or service not known", "failed to resolve", "getaddrinfo", "name resolution", "nodename nor servname") ->
                "Cannot reach the server" to true
            has("connection reset", "connection refused", "connection aborted", "remote end closed", "incompleteread",
                "network is unreachable", "unable to download", "urlopen error", "ssl", "eof occurred", "incomplete download") ->
                "Connection interrupted" to true
            else -> {
                // yt-dlp's own "ERROR: ..." line, else the last meaningful line (e.g. a Python error)
                val d = Regex("ERROR:\\s*([^\\n]{1,120})").find(raw)?.groupValues?.get(1)
                    ?: raw.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING") }?.take(120)
                (if (!d.isNullOrEmpty()) "Download failed: $d" else "Download failed (unknown reason)") to false
            }
        }
    }

    // ---------- download folder ----------
    private fun updateFolderLabel() {
        val t = prefs.getString("tree", null)
        // A picked folder's id looks like "primary:Download/Videos"; show the part after the colon.
        val name = if (t == null) "Download/VidGrab" else try {
            DocumentsContract.getTreeDocumentId(Uri.parse(t)).substringAfter(':').ifEmpty { "Internal storage" }
        } catch (_: Exception) { "custom folder" }
        folderBtn.text = "Save to: $name  ·  Change"
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 1)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 3 && res == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) optimizePicked(uris)
            return
        }
        if (req == 1 && res == RESULT_OK) {
            val u = data?.data ?: return
            contentResolver.takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("tree", u.toString()).apply()
            updateFolderLabel()
        }
    }

    // ---------- list of videos ----------
    /** Checks every new link in the paste box automatically and lists its available resolutions. */
    private fun autoCheck() {
        if (!ready) return
        val inText = Regex("https?://\\S+").findAll(input.text).map { it.value }.distinct().toList()
        val newly = inText.filter { it !in prevLinks }
        prevLinks = inText.toSet()
        failed.removeAll(newly.toSet())          // a link you paste again is tried again
        failed.retainAll(inText.toSet())
        val fresh = inText.filter { u -> items.none { it.url == u } && u !in failed && u !in checking }
        if (fresh.isEmpty()) {
            if (newly.any { n -> items.any { it.url == n } }) status.text = "That link is already in your list below."
            return
        }
        status.text = "Checking ${fresh.size} link(s)..."
        checking.addAll(fresh)
        analyzer.execute {
            val problems = mutableListOf<String>()
            val details = StringBuilder()
            var added = 0
            for ((n, u) in fresh.withIndex()) {
                runOnUiThread { status.text = "Checking link ${n + 1} of ${fresh.size}..." }
                try {
                    val req = YoutubeDLRequest(u)
                    req.addOption("--dump-single-json")
                    req.addOption("--no-playlist")
                    req.addOption("--socket-timeout", "20")
                    val j = JSONObject(YoutubeDL.getInstance().execute(req).out)
                    val sizes = mutableMapOf<Int, Long>()
                    val fm = j.optJSONArray("formats")
                    for (k in 0 until (fm?.length() ?: 0)) {
                        val f = fm!!.getJSONObject(k)
                        val h = f.optInt("height", 0)
                        if (h <= 0 || f.optString("vcodec", "") == "none") continue
                        val sz = f.optLong("filesize", f.optLong("filesize_approx", 0))
                        if (sz >= (sizes[h] ?: -1L)) sizes[h] = sz
                    }
                    val labels = mutableListOf<String>()
                    val choices = mutableListOf<Int>()
                    for (h in sizes.keys.sortedDescending()) {
                        val sz = sizes[h] ?: 0L
                        labels.add("${h}p" + if (sz > 0) " (~${sz / 1_000_000} MB)" else "")
                        choices.add(h)
                    }
                    labels.add("Best available"); choices.add(0)
                    labels.add("Audio only (MP3)"); choices.add(-1)
                    val dur = j.optDouble("duration", 0.0).toLong()
                    val meta = listOf(j.optString("extractor_key", ""), if (dur > 0) fmt(dur) else "",
                        j.optString("uploader", ""))
                        .filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
                    val item = Item(u, j.optString("title", u), labels, choices, j.optString("thumbnail", ""), meta)
                    runOnUiThread {
                        if (items.none { it.url == u }) {
                            addRow(item)
                            item.check.isChecked = true
                            persist()
                        }
                        checking.remove(u)
                    }
                    added++
                } catch (e: Exception) {
                    val (msg, net) = why(e)
                    if (!net) failed.add(u)
                    checking.remove(u)
                    problems.add("Link ${n + 1}: $msg")
                    details.append(u).append('\n').append(rawError(e)).append("\n\n")
                }
            }
            checkErr = details.toString().trim()
            runOnUiThread {
                val head = if (added > 0) "Pick a resolution for each video, then tap Download." else ""
                val tail = if (problems.isNotEmpty()) "Tap here for details." else ""
                status.text = (listOf(head) + problems + tail).filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
    }

    /** Removes a link from the paste box once its download has started. */
    private fun removeFromInput(u: String) {
        val t = input.text.toString()
        if (u in t) input.setText(t.lines().filter { !it.contains(u) }.joinToString("\n"))
    }

    private fun addRow(a: Item) {
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        a.check = CheckBox(this).apply {
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            setOnCheckedChangeListener { _, _ -> updateCounts() }
        }
        // thumbnail: a play icon on a soft tile until the picture has loaded
        a.thumbView = ImageView(this).apply {
            background = bg(C_SOFT, 10)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(android.R.drawable.ic_media_play)
            imageTintList = ColorStateList.valueOf(C_PRIMARY)
        }
        loadThumb(a)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = a.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(C_TEXT)
        })
        if (a.meta.isNotEmpty()) titles.addView(TextView(this).apply {
            text = a.meta; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 12f; setTextColor(C_MUTED); setPadding(0, px(2), 0, 0)
        })
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(a.check, LinearLayout.LayoutParams(wrap, wrap))
        top.addView(a.thumbView, LinearLayout.LayoutParams(px(96), px(54)))
        top.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))

        a.spinner = Spinner(this)
        a.spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, a.labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val quality = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quality.addView(TextView(this).apply { text = "Quality"; textSize = 13f; setTextColor(C_MUTED) })
        quality.addView(a.spinner, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(8) })

        a.bar = bar()
        a.state = TextView(this).apply {
            text = "Ready to download"; textSize = 13f; setTextColor(C_MUTED)
            setOnClickListener { if (a.err.isNotEmpty()) showDetails(a.title, a.err) }
        }
        // The two buttons change role: Start/Pause + Stop while unfinished, Open + Share when done.
        a.toggle = btn("Start", PRIMARY) {
            when {
                a.done -> openItem(a)
                a.busy && !a.paused && !a.cancelled -> pauseItem(a)
                else -> startItem(a)
            }
        }
        a.stopBtn = btn("Stop", DANGER) { if (a.done) shareItem(a) else stopItem(a) }
        for (x in listOf(a.toggle, a.stopBtn)) { x.minHeight = px(38); x.minimumHeight = px(38) }

        val box = card()
        add(box, top, 2)
        add(box, quality, 2)
        box.addView(a.bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(8))
            .apply { setMargins(0, px(8), 0, px(4)) })
        add(box, a.state, 2)
        add(box, row(a.toggle, a.stopBtn), 4)
        a.box = box
        if (a.done) {
            a.bar.progress = 100
            setState(a, "Done - saved", C_OK)
        }
        list.addView(a.box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, wrap)
            .apply { setMargins(0, px(6), 0, px(6)) })
        items.add(a)
        refreshButtons(a)
        updateCounts()
    }

    /** Video count in the list heading, "Select all" state, empty hint, and the Download button label. */
    private fun updateCounts() {
        val n = items.size
        val sel = items.count { it.check.isChecked && !it.done }
        countLabel.text = if (n == 0) "YOUR VIDEOS" else "YOUR VIDEOS ($n)"
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        dlBtn.text = if (sel > 0) "Download ($sel)" else "Download"
        syncing = true
        selectAll.isChecked = n > 0 && items.all { it.check.isChecked }
        syncing = false
    }

    /** Loads (and caches) the video's thumbnail into its card. */
    private fun loadThumb(a: Item) {
        if (!a.thumb.startsWith("http")) return
        val v = a.thumbView
        imgPool.execute {
            try {
                val f = File(cacheDir, "thumbs/" + Integer.toHexString(a.url.hashCode()))
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    val c = java.net.URL(a.thumb).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 10_000
                    try {
                        if (c.responseCode != 200) return@execute
                        val tmp = File(f.path + ".tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        tmp.renameTo(f)
                    } finally {
                        c.disconnect()
                    }
                }
                // decode at about the size it is shown, not the full resolution
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= px(96)) s *= 2
                val bmp = android.graphics.BitmapFactory.decodeFile(f.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: return@execute
                runOnUiThread {
                    v.imageTintList = null
                    v.scaleType = ImageView.ScaleType.CENTER_CROP
                    v.setImageBitmap(bmp)
                }
            } catch (_: Exception) {}
        }
    }

    private fun openItem(a: Item) {
        val u = Uri.parse(a.files.firstOrNull() ?: return)
        try {
            startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, contentResolver.getType(u) ?: "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            status.text = "No app found to open this file (or it was deleted)."
        }
    }

    private fun shareItem(a: Item) {
        if (a.files.isEmpty()) return
        val uris = ArrayList(a.files.map { Uri.parse(it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = (try { contentResolver.getType(uris[0]) } catch (_: Exception) { null }) ?: "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Share")) } catch (_: Exception) {}
    }

    /** Everything that is not the main Download action. */
    private fun showMenu(anchor: View) {
        val busy = items.any { it.busy }
        // Counts in the labels (and greyed-out items) show at a glance what each action would touch.
        val ticked = items.filter { it.check.isChecked }
        val tickedUnfinished = ticked.count { !it.done }
        val tickedWithFiles = ticked.count { it.files.isNotEmpty() }
        val completed = items.count { it.done }
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 1, "Remove completed ($completed)").isEnabled = completed > 0
            menu.add(0, 2, 2, "Remove ticked unfinished ($tickedUnfinished)").isEnabled = tickedUnfinished > 0
            menu.add(0, 3, 3, "Delete ticked videos (${ticked.size})").isEnabled = ticked.isNotEmpty()
            menu.add(0, 4, 4, "Optimize ticked files ($tickedWithFiles)").isEnabled = tickedWithFiles > 0
            menu.add(0, 5, 5, "Optimize a video from my phone").isEnabled = ready
            menu.add(0, 6, 6, when {
                updating -> "Updating engine..."
                busy -> "Update engine (after downloads finish)"
                else -> "Update engine"
            }).isEnabled = ready && !updating && !busy
            menu.add(0, 7, 7, "Remove all (${items.size})").isEnabled = items.isNotEmpty()
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> removeCompleted()
                    2 -> removeUnfinished()
                    3 -> deleteTicked()
                    4 -> optimizeFiles()
                    5 -> pickVideos()
                    6 -> update()
                    7 -> removeAll()
                }
                true
            }
            show()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in items) arr.put(JSONObject().apply {
            put("url", a.url); put("title", a.title); put("thumb", a.thumb); put("meta", a.meta)
            put("labels", JSONArray(a.labels)); put("choices", JSONArray(a.choices))
            put("sel", a.spinner.selectedItemPosition); put("done", a.done); put("pct", a.pct)
            put("files", JSONArray(a.files))
        })
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun restore() {
        try {
            val arr = JSONArray(prefs.getString("items", null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val lj = o.getJSONArray("labels")
                val cj = o.getJSONArray("choices")
                val a = Item(o.getString("url"), o.getString("title"),
                    List(lj.length()) { lj.getString(it) }, List(cj.length()) { cj.getInt(it) },
                    o.optString("thumb", ""), o.optString("meta", ""))
                a.done = o.optBoolean("done")
                a.pct = o.optInt("pct", -1)
                o.optJSONArray("files")?.let { f -> for (k in 0 until f.length()) a.files.add(f.getString(k)) }
                addRow(a)
                a.spinner.setSelection(o.optInt("sel"))
                if (!a.done && a.pct >= 0) {
                    a.bar.progress = a.pct
                    setState(a, pausedText(a))
                }
            }
        } catch (_: Exception) {}
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        if (autoConfirm) { ok(); return }
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> ok() }
            .setNegativeButton("No", null).show()
    }

    private fun choiceOf(a: Item) = a.choices[a.spinner.selectedItemPosition]

    // Same link + same resolution always maps to the same folder, so a retry continues the partial file.
    private fun dirFor(a: Item, choice: Int) = File(filesDir, "dl/" + Integer.toHexString((a.url + choice).hashCode()))

    private fun removeCompleted() {
        val gone = items.filter { it.done }
        if (gone.isEmpty()) { tell("No finished downloads to remove yet"); return }
        dropFromList(gone)
        tell("Removed ${gone.size} completed item(s). Files are kept.")
    }

    /** Deletes the partial-download folders of every resolution of this item, except one still in use. */
    private fun deletePartials(a: Item, keep: File? = null) {
        for (c in a.choices) { val d = dirFor(a, c); if (d != keep) d.deleteRecursively() }
    }

    /** Cancels an item (if running) and discards its partial files. A running worker deletes its own folder. */
    private fun discard(a: Item) {
        a.cancelled = true
        val busy = a.busy
        if (busy) kill(a)
        deletePartials(a, if (busy) a.runDir else null)
    }

    /** Removes partial-download folders that no item in the list uses any more. */
    private fun sweepPartials() {
        val keep = items.flatMap { a -> a.choices.map { dirFor(a, it).name } }.toSet()
        analyzer.execute {
            File(filesDir, "dl").listFiles()?.forEach {
                if (it.name !in keep && it.name !in running) it.deleteRecursively()
            }
        }
    }

    /**
     * Shows a result both in the status bubble and as a toast: the bubble sits at the top of the page,
     * out of sight when the list is scrolled, which made these actions look like they did nothing.
     */
    private fun tell(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Takes items off the list (and off the screen). Call after discard() or when they are finished. */
    private fun dropFromList(sel: List<Item>) {
        for (a in sel) {
            list.removeView(a.box)
            items.remove(a)
        }
        persist()
        refreshService()
        updateCounts()
    }

    private fun removeUnfinished() {
        val ticked = items.filter { it.check.isChecked }
        val sel = ticked.filter { !it.done }
        if (sel.isEmpty()) {
            tell(if (ticked.isEmpty()) "Tick the unfinished downloads you want to remove first"
                else "The ticked videos are already finished - use \"Delete ticked videos\" or \"Remove completed\"")
            return
        }
        confirm("Remove ${sel.size} unfinished download(s) from the list? Their partial files are discarded.") {
            for (a in sel) discard(a)
            dropFromList(sel)
            tell("Removed ${sel.size} unfinished download(s).")
        }
    }

    private fun removeAll() {
        if (items.isEmpty()) { tell("The list is already empty"); return }
        confirm("Remove every item from the list, including unfinished ones? Partial downloads are discarded. Saved files are kept.") {
            val all = items.toList()
            for (a in all) discard(a)
            dropFromList(all)
            tell("List cleared.")
        }
    }

    /** Deletes one saved file. A file that is already gone counts as deleted. */
    private fun deleteSaved(f: String): Boolean = try {
        val u = Uri.parse(f)
        if (u.authority == MediaStore.AUTHORITY) contentResolver.delete(u, null, null)
        else DocumentsContract.deleteDocument(contentResolver, u)
        true
    } catch (_: java.io.FileNotFoundException) {
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Deletes the ticked videos, finished or not: their saved files are deleted from the phone,
     * unfinished downloads are stopped and their partial files discarded, and the cards are removed.
     */
    private fun deleteTicked() {
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { tell("Tick the videos you want to delete first"); return }
        val savedFiles = sel.fold(0) { n, a -> n + a.files.size }
        val msg = "Delete ${sel.size} ticked video(s)?\n\n" +
            (if (savedFiles > 0) "$savedFiles saved file(s) will be deleted from your phone.\n" else "") +
            "Unfinished downloads are stopped and discarded. The videos are removed from the list."
        confirm(msg) {
            var notDeleted = 0
            for (a in sel) {
                for (f in a.files) if (!deleteSaved(f)) notDeleted++
                a.files.clear()
                discard(a)
            }
            dropFromList(sel)
            tell(if (notDeleted == 0) "Deleted ${sel.size} video(s)."
                else "Deleted ${sel.size} video(s) from the list, but $notDeleted file(s) could not be deleted " +
                    "(VidGrab may no longer own them after a reinstall). Delete them in your Files app.")
        }
    }

    // ---------- optimize with the bundled ffmpeg ----------
    private fun ffmpegBin() = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /**
     * The bundled ffmpeg needs libraries from BOTH the library's python and ffmpeg folders (since
     * youtubedl-android 0.18 it links libc++_shared, libcrypto, libexpat, ... from the python one).
     * This is the same search path the library gives ffmpeg when yt-dlp merges a download.
     */
    private fun ffmpegEnv(pb: ProcessBuilder) {
        val pk = File(noBackupFilesDir, "youtubedl-android/packages")
        pb.environment()["LD_LIBRARY_PATH"] =
            listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(pk, "$it/usr/lib").absolutePath }
        pb.environment()["TMPDIR"] = cacheDir.absolutePath
    }

    /**
     * Rebuilds the container (no re-encoding): fixes timestamps, drops broken packets, puts the index first.
     * Returns null when the file was optimized, otherwise the reason (with ffmpeg's error output).
     */
    private fun optimize(f: File): String? {
        val ext = f.extension.lowercase()
        if (ext !in listOf("mp4", "m4v", "mov", "mkv", "webm"))
            return "$NOT_VIDEO (.$ext) - only MP4, M4V, MOV, MKV and WEBM can be optimized"
        if (!ffmpegBin().exists()) return "The video tools (ffmpeg) are missing from this build"
        val dir = f.parentFile ?: return "Bad file location"
        if (dir.usableSpace < f.length() + 20_000_000L) return "Not enough free space to optimize"
        val out = File(dir, f.nameWithoutExtension + ".opt." + ext)
        try {
            val cmd = mutableListOf(ffmpegBin().absolutePath, "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                "-fflags", "+genpts+discardcorrupt", "-i", f.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero")
            if (ext != "mkv" && ext != "webm") { cmd.add("-movflags"); cmd.add("+faststart") }
            cmd.add(out.absolutePath)
            val pb = ProcessBuilder(cmd)
            ffmpegEnv(pb)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val log = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0 && out.length() > f.length() / 2) {
                f.delete()
                return if (out.renameTo(f)) null else "Could not replace the file with the optimized one"
            }
            out.delete()
            return when {
                code == 0 -> "ffmpeg's result looked wrong, so the file was left unchanged"
                log.isEmpty() -> "ffmpeg failed (exit code $code)"
                else -> "ffmpeg failed (exit code $code):\n" + log.takeLast(1500)
            }
        } catch (e: Exception) {
            out.delete()
            return "Could not run ffmpeg: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun optimizeFiles() {
        val sel = items.filter { it.check.isChecked && it.files.isNotEmpty() }
        if (sel.isEmpty()) { tell("Tick finished videos first - only downloaded files can be optimized"); return }
        status.text = "Optimizing ${sel.size} item(s)..."
        analyzer.execute {
            var fixed = 0
            for (a in sel) {
                ui(a, "Optimizing...", C_PRIMARY)
                var ok = false
                val errs = mutableListOf<String>()
                for (s in a.files) {
                    val u = Uri.parse(s)
                    val ext = (try {
                        MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(u))
                    } catch (_: Exception) { null }) ?: "mp4"
                    val tmp = File(cacheDir, "opt/${System.nanoTime()}.$ext").apply { parentFile?.mkdirs() }
                    try {
                        contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        val r = optimize(tmp)
                        if (r == null) {
                            contentResolver.openOutputStream(u, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                            ok = true
                        } else errs.add(r)
                    } catch (e: Exception) {
                        errs.add(rawError(e))
                    }
                    tmp.delete()
                }
                if (ok) fixed++
                a.err = errs.joinToString("\n\n")
                ui(a, when {
                    ok && errs.isEmpty() -> "Optimized"
                    ok -> "Partly optimized - tap here for details"
                    errs.all { it.startsWith(NOT_VIDEO) } -> "Audio files don't need optimizing"
                    else -> "Could not optimize (file left unchanged) - tap here for details"
                }, if (ok) C_OK else C_DANGER)
            }
            runOnUiThread { tell("Optimized $fixed of ${sel.size} item(s).") }
        }
    }

    // ---------- clipboard + optimize a video from the phone ----------
    private fun pasteClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        val urls = Regex("https?://\\S+").findAll(t).map { it.value }.toList()
        if (urls.isEmpty()) { status.text = "No link found in the clipboard"; return }
        input.append(urls.joinToString("\n") + "\n")
    }

    private fun pickVideos() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, 3)
    }

    private fun displayName(u: Uri): String? =
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Optimizes videos chosen from the phone; each result is saved as a new "(optimized)" copy. */
    private fun optimizePicked(uris: List<Uri>) {
        status.text = "Optimizing ${uris.size} video(s)..."
        analyzer.execute {
            var ok = 0
            val errs = StringBuilder()
            for ((n, u) in uris.withIndex()) {
                runOnUiThread { status.text = "Optimizing video ${n + 1} of ${uris.size}..." }
                val name = (displayName(u) ?: "video.mp4").replace("/", "_")
                val ext = name.substringAfterLast('.', "mp4")
                val base = name.substringBeforeLast('.', name)
                val dir = File(cacheDir, "opt/${System.nanoTime()}").apply { mkdirs() }
                val tmp = File(dir, "in.$ext")
                try {
                    contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val r = optimize(tmp)
                    if (r == null) {
                        val out = File(dir, "$base (optimized).$ext")
                        tmp.renameTo(out)
                        save(out)
                        ok++
                    } else errs.append(name).append(":\n").append(r).append("\n\n")
                } catch (e: Exception) {
                    errs.append(name).append(":\n").append(rawError(e)).append("\n\n")
                }
                dir.deleteRecursively()
            }
            if (errs.isNotEmpty()) checkErr = errs.toString().trim()
            runOnUiThread {
                val saved = "Saved as \"... (optimized)\" in your download folder; the originals are untouched."
                tell(when {
                    ok == uris.size -> "Optimized ${uris.size} video(s). $saved"
                    ok > 0 -> "Optimized $ok of ${uris.size} video(s). $saved Tap here for details on the others."
                    else -> "Could not optimize. Tap here for details."
                })
            }
        }
    }

    // ---------- downloading ----------
    private fun downloadSelected() {
        if (items.isEmpty()) { status.text = "Paste links above first"; return }
        if (!ready) { status.text = "The download engine is still starting - try again in a moment"; return }
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { tell("Tick the videos you want to download"); return }
        sel.forEach { startItem(it) }
        if (online() && !canDownload())
            status.text = "Waiting for Wi-Fi. Turn off \"Wi-Fi only\" in Settings to use mobile data."
    }

    private fun startItem(a: Item) {
        if (a.busy || a.done) return
        if (!ready) { setState(a, "The download engine is still starting - try again in a moment", C_WARN); return }
        a.err = ""
        val choice = choiceOf(a)
        val dir = dirFor(a, choice).apply { mkdirs() }
        if (!running.add(dir.name)) { setState(a, "Already running in the background", C_WARN); return }
        removeFromInput(a.url)
        a.runDir = dir
        a.busy = true
        a.cancelled = false
        a.paused = false
        setState(a, at(a) + "Queued...", C_PRIMARY)
        refreshButtons(a)
        refreshService()
        persist()
        downloader.execute { work(a, choice, dir) }
    }

    /** Pause keeps the partial file; Start continues from it. */
    private fun pauseItem(a: Item) {
        a.paused = true
        setState(a, "Pausing...")
        kill(a)
        refreshButtons(a)
    }

    /** Stop discards the partial files (of every resolution); Start then begins from zero. */
    private fun stopItem(a: Item) {
        if (a.done) return
        if (a.busy) {
            setState(a, "Stopping...")
            discard(a)
        } else {
            deletePartials(a)
            a.pct = -1
            a.bar.progress = 0
            setState(a, "Stopped")
        }
        refreshButtons(a)
    }

    private fun refreshButtons(a: Item) {
        if (a.done) {
            val has = a.files.isNotEmpty()
            a.toggle.text = "Open"; style(a.toggle, PRIMARY); a.toggle.isEnabled = has
            a.stopBtn.text = "Share"; style(a.stopBtn, SOFT); a.stopBtn.isEnabled = has
        } else {
            val stopping = a.busy && (a.paused || a.cancelled)
            a.toggle.text = when {
                stopping -> "Wait..."
                a.busy -> "Pause"
                else -> "Start"
            }
            style(a.toggle, if (a.busy) SOFT else PRIMARY)
            a.toggle.isEnabled = !stopping
            a.stopBtn.text = "Stop"; style(a.stopBtn, DANGER)
            a.stopBtn.isEnabled = !(a.busy && a.cancelled)
        }
        a.spinner.isEnabled = !a.busy && !a.done
        a.bar.progressTintList = ColorStateList.valueOf(if (a.done) C_OK else C_PRIMARY)
    }

    /** Keeps the background service (and its notification) running while any download is active. */
    private fun refreshService() {
        val n = items.count { it.busy }
        try {
            val i = Intent(this, DownloadService::class.java)
            if (n > 0) {
                if (!svcOn) { startForegroundService(i); svcOn = true }
                getSystemService(NotificationManager::class.java)
                    .notify(1, DownloadService.build(this, "$n download(s) running"))
            } else if (svcOn) {
                stopService(i)
                svcOn = false
            }
        } catch (_: Exception) {}
    }

    /** Sets an item's status line; tone colors it (muted, blue = active, amber = waiting, red = error, green = done). */
    private fun setState(a: Item, text: String, tone: Int = C_MUTED) {
        a.state.text = text
        a.state.setTextColor(tone)
    }

    private fun ui(a: Item, text: String, tone: Int = C_MUTED) = runOnUiThread {
        if (a.pct >= 0) a.bar.progress = a.pct
        setState(a, text, tone)
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)

    private fun rateStr(b: Double) = when {
        b >= 1_000_000 -> "%.1f MB/s".format(b / 1_000_000)
        b >= 1_000 -> "%.0f KB/s".format(b / 1_000)
        else -> "%.0f B/s".format(b)
    }

    /** "45% - " when the progress is known, so it stays visible in every status message. */
    private fun at(a: Item) = if (a.pct >= 0) "${a.pct}% - " else ""

    private fun pausedText(a: Item) = at(a) + "Paused - tap Start to continue"

    private fun buildRequest(url: String, choice: Int, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--continue")
        r.addOption("--retries", "3")
        r.addOption("--fragment-retries", "3")
        r.addOption("--retry-sleep", "2")
        r.addOption("--socket-timeout", "20")
        // Request big files in 10 MB pieces (servers throttle long single requests less) and fetch
        // 4 fragments at once for HLS/DASH streams.
        r.addOption("--http-chunk-size", "10M")
        r.addOption("--concurrent-fragments", "4")
        // Print progress in our own fixed format, one line each. The library's parser needs
        // "ETA mm:ss" and drops lines like "ETA Unknown" (normal right after a resume).
        r.addOption("--newline")
        r.addOption("--progress-template", "download:" + PROG +
            "%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|" +
            "%(progress.speed)s|%(progress.eta)s|%(progress.fragment_index)s|%(progress.fragment_count)s")
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        when (choice) {
            -1 -> { r.addOption("-x"); r.addOption("--audio-format", "mp3") }
            0 -> {
                r.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                r.addOption("--merge-output-format", "mp4")
            }
            else -> {
                r.addOption("-f", "bv*[height<=$choice][ext=mp4]+ba[ext=m4a]/bv*[height<=$choice]+ba/b[height<=$choice]")
                r.addOption("--merge-output-format", "mp4")
            }
        }
        return r
    }

    /** Handles one line of yt-dlp output; returns true when more bytes have arrived. */
    private fun onLine(a: Item, line: String): Boolean {
        if (a.paused || a.cancelled) { kill(a); return false }
        if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
            line.startsWith("[VideoConvertor]") || line.startsWith("[Fixup")) {
            a.post = true
            ui(a, "Merging / converting...", C_PRIMARY)
            return false
        }
        val i = line.indexOf(PROG)
        if (i < 0) return false
        val p = line.substring(i + PROG.length).split('|')
        if (p.size < 7) return false
        fun num(k: Int) = p[k].trim().toDoubleOrNull()     // yt-dlp prints "NA" for unknown values
        val got = num(0)
        val total = num(1) ?: num(2)
        val speed = num(3)
        val eta = num(4)
        val fi = num(5)
        val fc = num(6)
        val pct = when {
            got != null && total != null && total > 0 -> got / total * 100
            fi != null && fc != null && fc > 0 -> fi / fc * 100
            else -> null
        }
        if (pct != null) a.pct = pct.toInt().coerceIn(0, 100)
        val now = System.currentTimeMillis()
        var moved = false
        if (got != null) {
            // A smaller count means the next stream (e.g. audio after video) started: measure it afresh.
            if (a.runBytes0 < 0 || got < a.lastBytes) { a.runBytes0 = got; a.runStart = now }
            moved = a.lastBytes >= 0 && got > a.lastBytes
            a.lastBytes = got
        }
        if (speed != null && speed > 0 && !a.afterNet && speed > a.peak) {
            a.peak = speed
            a.peakOnWifi = a.runOnWifi
        }
        checkSlow(a, now)
        if (now - a.lastUi >= 300 || (pct ?: 0.0) >= 100) {
            a.lastUi = now
            val parts = mutableListOf<String>()
            if (a.pct >= 0) parts.add("${a.pct}%")
            if (speed != null && speed > 0) parts.add(rateStr(speed)) else if (a.pct < 100) parts.add("connecting...")
            if (eta != null && eta >= 0) parts.add("ETA " + fmt(eta.toLong()))
            ui(a, parts.joinToString("  ·  "), C_PRIMARY)
        }
        return moved
    }

    /**
     * After an interruption a download sometimes comes back much slower than before (a stale connection
     * or a throttled server). If it stays under a third of its earlier speed for 20 s, start it again
     * from the partial file - at most twice per interruption.
     */
    private fun checkSlow(a: Item, now: Long) {
        if (!a.afterNet || a.post || a.runBytes0 < 0 || a.lastBytes < 0) return
        // Nothing to compare with, or a different kind of network (mobile data is often just slower).
        if (a.peak <= 0 || a.peakOnWifi != a.runOnWifi) { a.afterNet = false; return }
        val secs = (now - a.runStart) / 1000.0
        if (secs < 20) return
        val avg = (a.lastBytes - a.runBytes0) / secs
        if (avg >= a.peak / 3 || a.slowRestarts >= 2) { a.afterNet = false; return }
        a.slowRestarts++
        a.netKill = true
        kill(a)
    }

    private fun work(a: Item, choice: Int, dir: File) {
        var tries = 0
        try {
            while (true) {
                if (a.cancelled) return
                if (a.paused) { ui(a, pausedText(a)); return }
                if (!canDownload()) {
                    ui(a, at(a) + (if (online()) "Waiting for Wi-Fi - continues automatically"
                        else "Waiting for internet - continues automatically"), C_WARN)
                    while (!canDownload() && !a.cancelled && !a.paused) Thread.sleep(1500)
                    continue
                }
                try {
                    a.netKill = false
                    a.post = false
                    a.runOnWifi = onWifiNow()
                    a.runBytes0 = -1.0
                    a.lastBytes = -1.0
                    a.pid = dir.name + "-" + System.nanoTime()
                    ui(a, if (a.pct >= 0) "${a.pct}% - resuming..." else "Starting...", C_PRIMARY)
                    a.proc = true
                    try {
                        YoutubeDL.getInstance().execute(buildRequest(a.url, choice, dir), a.pid) { _, _, line ->
                            if (onLine(a, line)) tries = 0
                        }
                    } finally {
                        a.proc = false
                    }
                    // A killed process can still end "normally"; never treat its leftovers as a finished file.
                    if (a.netKill || a.paused || a.cancelled) throw Exception("interrupted")
                    val files = dir.listFiles().orEmpty()
                        .filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
                    if (files.isEmpty()) throw Exception("incomplete download")
                    // Optimizing is a bonus: if it fails the download is still saved, and the card says why.
                    var optErr = ""
                    if (prefs.getBoolean("optimize", true)) {
                        ui(a, "Optimizing video...", C_PRIMARY)
                        optErr = files.mapNotNull { optimize(it) }.filter { !it.startsWith(NOT_VIDEO) }
                            .joinToString("\n\n")
                    }
                    val saved = files.map { save(it) }
                    dir.deleteRecursively()
                    a.files.addAll(saved)
                    a.pct = 100
                    a.done = true
                    a.err = if (optErr.isEmpty()) "" else "Saved, but optimizing failed:\n\n$optErr"
                    runOnUiThread {
                        a.bar.progress = 100
                        if (optErr.isEmpty()) setState(a, "Done - saved", C_OK)
                        else setState(a, "Done - saved (not optimized - tap here for details)", C_WARN)
                        persist()
                        updateCounts()
                    }
                    return
                } catch (e: Exception) {
                    if (a.cancelled) return
                    if (a.paused) { ui(a, pausedText(a)); return }
                    if (a.netKill) continue     // stopped because the network changed: resume right away
                    val (msg, net) = why(e)
                    if (net && tries < 30) {
                        tries++
                        a.afterNet = true
                        a.slowRestarts = 0
                        ui(a, at(a) + "$msg - retrying...", C_WARN)
                        if (online()) Thread.sleep(3000)
                        continue
                    }
                    a.err = rawError(e)
                    ui(a, at(a) + "$msg. Tap Start to retry (progress is kept) or tap here for details.", C_DANGER)
                    return
                }
            }
        } finally {
            a.busy = false
            a.proc = false
            a.runDir = null
            running.remove(dir.name)
            if (a.cancelled) { dir.deleteRecursively(); a.pct = -1 }
            runOnUiThread {
                if (a.cancelled) { a.bar.progress = 0; setState(a, "Stopped") }
                refreshButtons(a)
                refreshService()
            }
        }
    }

    /** Copies a finished file to the chosen folder; returns its uri so it can be deleted later. */
    private fun save(f: File): String {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "application/octet-stream"
        val tree = prefs.getString("tree", null)
        val uri: Uri = if (tree != null) {
            val t = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            DocumentsContract.createDocument(contentResolver, parent, mime, f.name)
                ?: throw SecurityException("cannot create file")
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw SecurityException("cannot create file")
        }
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        return uri.toString()
    }

    // ---------- test hooks ----------
    // UI TEST CONTRACT: the end-to-end tests in app/src/androidTest drive the app ONLY through these
    // functions (plus the public fields of Item: url, title, labels, check, spinner, state, done, files, err).
    // A UI redesign may change any view, but must keep these names and what they do.
    // Must be called on the main thread (tests use ActivityScenario.onActivity).
    @Volatile internal var autoConfirm = false        // confirm() answers "Yes" without showing a dialog
    internal fun testReady() = ready
    internal fun testAddLinks(text: String) { input.append(text + "\n") }
    internal fun testItems(): List<Item> = items.toList()
    internal fun testStatus() = status.text.toString()
    internal fun testCheckErr() = checkErr
    internal fun testState(a: Item) = a.state.text.toString()
    internal fun testSelect(a: Item, label: String) { a.spinner.setSelection(a.labels.indexOf(label)) }
    internal fun testDownloadTicked() = downloadSelected()
    internal fun testOptimizeTicked() = optimizeFiles()
    internal fun testDeleteTicked() = deleteTicked()
    internal fun testRemoveUnfinished() = removeUnfinished()
    internal fun testRemoveAll() = removeAll()

    // ---------- engine update ----------
    private fun update() {
        if (!online()) { status.text = "No internet connection"; return }
        if (updating || items.any { it.busy }) return
        updating = true
        status.text = "Updating engine..."
        Thread {
            try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                val v = YoutubeDL.getInstance().versionName(application)
                prefs.edit().putLong("engineChecked", System.currentTimeMillis()).apply()
                runOnUiThread {
                    status.text = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                        "Already up to date ($v)" else "Updated to $v"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Update failed: " + why(e).first }
            } finally {
                updating = false
            }
        }.start()
    }
}
