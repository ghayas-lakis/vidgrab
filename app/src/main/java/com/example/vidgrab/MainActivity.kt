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
private const val PRIMARY = 0
private const val SOFT = 1
private const val DANGER = 2
// Marks our own progress lines (see --progress-template in buildRequest).
private const val PROG = "VGP|"

class MainActivity : Activity() {
    // choices: height in pixels, 0 = best available, -1 = audio only (MP3)
    class Item(val url: String, val title: String, val labels: List<String>, val choices: List<Int>) {
        lateinit var check: CheckBox
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
        var done = false
        val files = mutableListOf<String>()
    }

    private lateinit var input: EditText
    private lateinit var updBtn: Button
    private lateinit var folderBtn: Button
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
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

    private fun btn(text: String, kind: Int = SOFT, onClick: () -> Unit): Button {
        val fill = when (kind) { PRIMARY -> C_PRIMARY; DANGER -> C_DANGER; else -> C_SOFT }
        val fg = if (kind == SOFT) C_PRIMARY else Color.WHITE
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.stateListAnimator = null
        b.minHeight = px(44)
        b.minimumHeight = px(44)
        b.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), bg(Color.parseColor("#CBD3E6"), 12))
            addState(intArrayOf(android.R.attr.state_pressed), bg(darker(fill), 12))
            addState(intArrayOf(), bg(fill, 12))
        }
        b.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.parseColor("#7C879E"), fg)))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(px(3), 0, px(3), 0) })
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
        window.navigationBarColor = C_BOTTOM
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(18), px(14), px(24))
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
        titles.addView(TextView(this).apply { text = "Fast video downloader"; textSize = 13f; setTextColor(C_SUB) })
        head.addView(titles)
        add(root, head)

        // status line (right under the header so it is always visible)
        status = TextView(this).apply {
            text = "Starting engine..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(px(4), px(10), px(4), px(6))
        }
        add(root, status)

        // links card
        val links = card()
        input = EditText(this).apply {
            hint = "Paste links here (one per line) - they are checked automatically"
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
        add(links, btn("Paste from clipboard", PRIMARY) { pasteClipboard() })
        add(root, links, 6)

        // settings card
        val settings = card()
        folderBtn = btn("") { pickFolder() }
        add(settings, folderBtn)
        updateFolderLabel()
        add(settings, CheckBox(this).apply {
            text = "Optimize automatically after each download (untick to optimize later)"
            setTextColor(C_TEXT)
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            isChecked = prefs.getBoolean("optimize", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("optimize", c).apply() }
        })
        add(settings, CheckBox(this).apply {
            text = "Download on Wi-Fi only (untick to also use mobile data)"
            setTextColor(C_TEXT)
            buttonTintList = ColorStateList.valueOf(C_PRIMARY)
            isChecked = wifiOnly()
            setOnCheckedChangeListener { _, c ->
                prefs.edit().putBoolean("wifiOnly", c).apply()
                // Turned on while on mobile data: pause running downloads until Wi-Fi is back.
                // Turned off: waiting downloads notice within a couple of seconds and continue.
                if (c && !canDownload()) restartRunning()
            }
        })
        add(root, settings, 6)

        // video list
        add(root, CheckBox(this).apply {
            text = "Select all"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(Color.WHITE)
            setOnCheckedChangeListener { _, c -> items.forEach { it.check.isChecked = c } }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, list, 0)

        // actions card
        val actions = card()
        add(actions, btn("Download", PRIMARY) { downloadSelected() })
        add(actions, row(btn("Remove completed") { removeCompleted() }, btn("Remove unfinished") { removeUnfinished() }))
        add(actions, row(btn("Delete files", DANGER) { deleteFiles() }, btn("Optimize files") { optimizeFiles() }))
        add(actions, btn("Remove all", DANGER) { removeAll() })
        add(actions, btn("Optimize a video from my phone") { pickVideos() })
        updBtn = btn("Update engine") { update() }
        updBtn.isEnabled = false
        add(actions, updBtn)
        add(root, actions, 6)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_TOP, C_BOTTOM))
            addView(root)
        })
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
                ready = true
                runOnUiThread {
                    updBtn.isEnabled = true
                    status.text = "Ready (engine ${YoutubeDL.getInstance().versionName(application)}). Paste links above."
                    autoCheck()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not start the download engine" }
            }
        }.start()
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

    /** Returns a short readable cause, and whether it is a connection problem (worth retrying). */
    private fun why(e: Throwable): Pair<String, Boolean> {
        if (!online()) return "No internet connection" to true
        var raw = ""
        var c: Throwable? = e
        while (c != null) { raw += " " + (c.message ?: ""); c = c.cause }
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
                val d = Regex("ERROR:\\s*([^\\n]{1,100})").find(raw)?.groupValues?.get(1)
                (if (d != null) "Download failed: $d" else "Download failed (unknown reason)") to false
            }
        }
    }

    // ---------- download folder ----------
    private fun updateFolderLabel() {
        val t = prefs.getString("tree", null)
        val name = if (t == null) "Downloads/VidGrab" else Uri.parse(t).lastPathSegment ?: "custom folder"
        folderBtn.text = "Save to: $name (tap to change)"
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
                    val item = Item(u, j.optString("title", u), labels, choices)
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
                }
            }
            runOnUiThread {
                val head = if (added > 0) "Pick a resolution for each video, then tap Download." else ""
                status.text = (listOf(head) + problems).filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
    }

    /** Removes a link from the paste box once its download has started. */
    private fun removeFromInput(u: String) {
        val t = input.text.toString()
        if (u in t) input.setText(t.lines().filter { !it.contains(u) }.joinToString("\n"))
    }

    private fun addRow(a: Item) {
        a.check = CheckBox(this).apply { buttonTintList = ColorStateList.valueOf(C_PRIMARY) }
        a.spinner = Spinner(this)
        a.spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, a.labels)
        a.bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C_PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(C_SOFT)
        }
        a.state = TextView(this).apply { text = "Waiting"; textSize = 13f; setTextColor(C_MUTED) }
        a.toggle = btn("Start", PRIMARY) { if (a.busy && !a.paused && !a.cancelled) pauseItem(a) else startItem(a) }
        a.stopBtn = btn("Stop", DANGER) { stopItem(a) }
        for (x in listOf(a.toggle, a.stopBtn)) { x.minHeight = px(38); x.minimumHeight = px(38) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(content, TextView(this).apply {
            text = a.title; maxLines = 2; textSize = 16f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(C_TEXT)
        })
        add(content, a.spinner)
        add(content, a.bar)
        add(content, a.state)
        add(content, row(a.toggle, a.stopBtn))
        a.box = card().apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a.check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (a.done) {
            a.bar.progress = 100
            a.state.text = "Done - saved"
        }
        list.addView(a.box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, px(6), 0, px(6)) })
        items.add(a)
        refreshButtons(a)
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in items) arr.put(JSONObject().apply {
            put("url", a.url); put("title", a.title)
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
                    List(lj.length()) { lj.getString(it) }, List(cj.length()) { cj.getInt(it) })
                a.done = o.optBoolean("done")
                a.pct = o.optInt("pct", -1)
                o.optJSONArray("files")?.let { f -> for (k in 0 until f.length()) a.files.add(f.getString(k)) }
                addRow(a)
                a.spinner.setSelection(o.optInt("sel"))
                if (!a.done) {
                    if (a.pct >= 0) a.bar.progress = a.pct
                    a.state.text = pausedText(a)
                }
            }
        } catch (_: Exception) {}
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> ok() }
            .setNegativeButton("No", null).show()
    }

    private fun choiceOf(a: Item) = a.choices[a.spinner.selectedItemPosition]

    // Same link + same resolution always maps to the same folder, so a retry continues the partial file.
    private fun dirFor(a: Item, choice: Int) = File(filesDir, "dl/" + Integer.toHexString((a.url + choice).hashCode()))

    private fun removeCompleted() {
        val gone = items.filter { it.done }
        gone.forEach { list.removeView(it.box); items.remove(it) }
        persist()
        status.text = "Removed ${gone.size} completed item(s). Files are kept."
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

    private fun removeUnfinished() {
        val sel = items.filter { it.check.isChecked && !it.done }
        if (sel.isEmpty()) { status.text = "Tick the unfinished downloads you want to remove"; return }
        confirm("Remove ${sel.size} ticked unfinished download(s) from the list? Their partial files are discarded.") {
            for (a in sel) {
                discard(a)
                list.removeView(a.box)
                items.remove(a)
            }
            persist()
            refreshService()
            status.text = "Removed ${sel.size} unfinished download(s)."
        }
    }

    private fun removeAll() {
        if (items.isEmpty()) return
        confirm("Remove every item from the list, including unfinished ones? Partial downloads are discarded. Saved files are kept.") {
            for (a in items) discard(a)
            items.clear()
            list.removeAllViews()
            persist()
            status.text = "List cleared."
        }
    }

    private fun deleteFiles() {
        val sel = items.filter { it.check.isChecked && it.files.isNotEmpty() }
        if (sel.isEmpty()) { status.text = "Tick videos that have downloaded files first"; return }
        confirm("Delete the downloaded files of ${sel.size} ticked item(s) from your download folder?") {
            for (a in sel) {
                for (f in a.files) {
                    try {
                        val u = Uri.parse(f)
                        if (u.authority == MediaStore.AUTHORITY) contentResolver.delete(u, null, null)
                        else DocumentsContract.deleteDocument(contentResolver, u)
                    } catch (_: Exception) {}
                }
                a.files.clear()
                a.done = false
                a.bar.progress = 0
                a.state.text = "Files deleted"
                refreshButtons(a)
            }
            persist()
            status.text = "Deleted files of ${sel.size} item(s)."
        }
    }

    // ---------- optimize with the bundled ffmpeg ----------
    private fun ffmpegBin() = File(applicationInfo.nativeLibraryDir, "libffmpeg.so")

    /** Rebuilds the container (no re-encoding): fixes timestamps, drops broken packets, puts the index first. */
    private fun optimize(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext !in listOf("mp4", "m4v", "mov", "mkv", "webm")) return false
        val out = File(f.parentFile, f.nameWithoutExtension + ".opt." + ext)
        try {
            val cmd = mutableListOf(ffmpegBin().absolutePath, "-nostdin", "-y",
                "-fflags", "+genpts+discardcorrupt", "-i", f.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero")
            if (ext != "mkv" && ext != "webm") { cmd.add("-movflags"); cmd.add("+faststart") }
            cmd.add(out.absolutePath)
            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] =
                File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.readBytes()
            if (p.waitFor() == 0 && out.length() > f.length() / 2) {
                f.delete()
                return out.renameTo(f)
            }
        } catch (_: Exception) {}
        out.delete()
        return false
    }

    private fun optimizeFiles() {
        val sel = items.filter { it.check.isChecked && it.files.isNotEmpty() }
        if (sel.isEmpty()) { status.text = "Tick videos that have downloaded files first"; return }
        status.text = "Optimizing ${sel.size} item(s)..."
        analyzer.execute {
            var fixed = 0
            for (a in sel) {
                ui(a, "Optimizing...")
                var ok = false
                for (s in a.files) {
                    val u = Uri.parse(s)
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(u)) ?: "mp4"
                    val tmp = File(cacheDir, "opt/${System.nanoTime()}.$ext").apply { parentFile?.mkdirs() }
                    try {
                        contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        if (optimize(tmp)) {
                            contentResolver.openOutputStream(u, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                            ok = true
                        }
                    } catch (_: Exception) {}
                    tmp.delete()
                }
                if (ok) fixed++
                ui(a, if (ok) "Optimized" else "Could not optimize (file left unchanged)")
            }
            runOnUiThread { status.text = "Optimized $fixed of ${sel.size} item(s)." }
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
            for ((n, u) in uris.withIndex()) {
                runOnUiThread { status.text = "Optimizing video ${n + 1} of ${uris.size}..." }
                val name = (displayName(u) ?: "video.mp4").replace("/", "_")
                val ext = name.substringAfterLast('.', "mp4")
                val base = name.substringBeforeLast('.', name)
                val dir = File(cacheDir, "opt/${System.nanoTime()}").apply { mkdirs() }
                val tmp = File(dir, "in.$ext")
                try {
                    contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    if (optimize(tmp)) {
                        val out = File(dir, "$base (optimized).$ext")
                        tmp.renameTo(out)
                        save(out)
                        ok++
                    }
                } catch (_: Exception) {}
                dir.deleteRecursively()
            }
            runOnUiThread {
                status.text = if (ok > 0) "Optimized $ok of ${uris.size} video(s). Saved as \"... (optimized)\" in your download folder; the originals are untouched."
                    else "Could not optimize. Supported types: MP4, M4V, MOV, MKV, WEBM."
            }
        }
    }

    // ---------- downloading ----------
    private fun downloadSelected() {
        if (items.isEmpty()) { status.text = "Paste links above first"; return }
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { status.text = "Tick the videos you want to download"; return }
        sel.forEach { startItem(it) }
        if (online() && !canDownload())
            status.text = "Waiting for Wi-Fi. Untick \"Download on Wi-Fi only\" to use mobile data."
    }

    private fun startItem(a: Item) {
        if (a.busy || a.done) return
        val choice = choiceOf(a)
        val dir = dirFor(a, choice).apply { mkdirs() }
        if (!running.add(dir.name)) { a.state.text = "Already running in the background"; return }
        removeFromInput(a.url)
        a.runDir = dir
        a.busy = true
        a.cancelled = false
        a.paused = false
        a.state.text = "Queued..."
        refreshButtons(a)
        refreshService()
        persist()
        downloader.execute { work(a, choice, dir) }
    }

    /** Pause keeps the partial file; Start continues from it. */
    private fun pauseItem(a: Item) {
        a.paused = true
        a.state.text = "Pausing..."
        kill(a)
        refreshButtons(a)
    }

    /** Stop discards the partial files (of every resolution); Start then begins from zero. */
    private fun stopItem(a: Item) {
        if (a.done) return
        if (a.busy) {
            a.state.text = "Stopping..."
            discard(a)
        } else {
            deletePartials(a)
            a.pct = -1
            a.bar.progress = 0
            a.state.text = "Stopped"
        }
        refreshButtons(a)
    }

    private fun refreshButtons(a: Item) {
        val stopping = a.busy && (a.paused || a.cancelled)
        a.toggle.text = when {
            a.done -> "Done"
            stopping -> "Wait..."
            a.busy -> "Pause"
            else -> "Start"
        }
        a.toggle.isEnabled = !a.done && !stopping
        a.stopBtn.isEnabled = !a.done && !(a.busy && a.cancelled)
        a.spinner.isEnabled = !a.busy && !a.done
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

    private fun ui(a: Item, text: String) = runOnUiThread {
        if (a.pct >= 0) a.bar.progress = a.pct
        a.state.text = text
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
            ui(a, "Merging / converting...")
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
            ui(a, parts.joinToString(" | "))
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
                    ui(a, at(a) + if (online()) "Waiting for Wi-Fi - continues automatically (untick \"Wi-Fi only\" to use mobile data)"
                        else "Waiting for internet - continues automatically")
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
                    ui(a, if (a.pct >= 0) "${a.pct}% - resuming..." else "Starting...")
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
                    if (prefs.getBoolean("optimize", true)) {
                        ui(a, "Optimizing video...")
                        files.forEach { optimize(it) }
                    }
                    val saved = files.map { save(it) }
                    dir.deleteRecursively()
                    a.files.addAll(saved)
                    a.pct = 100
                    a.done = true
                    runOnUiThread { a.bar.progress = 100; a.state.text = "Done - saved"; persist() }
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
                        ui(a, at(a) + "$msg - retrying...")
                        if (online()) Thread.sleep(3000)
                        continue
                    }
                    ui(a, at(a) + "$msg. Tap Start to retry - progress is kept.")
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
                if (a.cancelled) { a.bar.progress = 0; a.state.text = "Stopped" }
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

    // ---------- engine update ----------
    private fun update() {
        if (!online()) { status.text = "No internet connection"; return }
        updBtn.isEnabled = false
        status.text = "Updating engine..."
        Thread {
            try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                val v = YoutubeDL.getInstance().versionName(application)
                runOnUiThread {
                    status.text = if (r == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                        "Already up to date ($v)" else "Updated to $v"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Update failed: " + why(e).first }
            } finally {
                runOnUiThread { updBtn.isEnabled = true }
            }
        }.start()
    }
}
