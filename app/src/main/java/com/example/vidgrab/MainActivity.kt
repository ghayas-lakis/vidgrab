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
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
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
        var done = false
        val files = mutableListOf<String>()
    }

    private lateinit var input: EditText
    private lateinit var analyzeBtn: Button
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

    companion object {
        // Downloads running in this process; survives the activity being recreated.
        private val running = ConcurrentHashMap.newKeySet<String>()
    }

    // When the connection drops, stop running downloads at once; the retry loop resumes them on reconnect.
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            for (a in items) if (a.busy) {
                try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
            }
        }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun add(parent: LinearLayout, v: View) = parent.addView(v, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        dp = resources.displayMetrics.density
        prefs = getSharedPreferences("vidgrab", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(40), px(16), px(24))
        }
        add(root, TextView(this).apply { text = "VidGrab"; textSize = 28f })
        input = EditText(this).apply {
            hint = "Paste one or more links (one per line)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }
        add(root, input)
        analyzeBtn = btn("Check links") { analyze() }
        analyzeBtn.isEnabled = false
        updBtn = btn("Update engine") { update() }
        updBtn.isEnabled = false
        add(root, analyzeBtn)
        folderBtn = btn("") { pickFolder() }
        add(root, folderBtn)
        updateFolderLabel()
        val selAll = CheckBox(this).apply {
            text = "Select all"
            setOnCheckedChangeListener { _, c -> items.forEach { it.check.isChecked = c } }
        }
        add(root, selAll)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, list)
        add(root, btn("Download") { downloadSelected() })
        add(root, row(btn("Remove completed") { removeCompleted() }, btn("Remove all") { removeAll() }))
        add(root, btn("Delete files") { deleteFiles() })
        add(root, updBtn)
        status = TextView(this).apply { text = "Starting engine..." }
        add(root, status)
        setContentView(ScrollView(this).apply { addView(root) })
        restore()
        handleIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb) } catch (_: Exception) {}
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                runOnUiThread {
                    analyzeBtn.isEnabled = true
                    updBtn.isEnabled = true
                    status.text = "Ready (engine ${YoutubeDL.getInstance().versionName(application)})."
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
        if (req == 1 && res == RESULT_OK) {
            val u = data?.data ?: return
            contentResolver.takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("tree", u.toString()).apply()
            updateFolderLabel()
        }
    }

    // ---------- list of videos ----------
    private fun analyze() {
        val urls = Regex("https?://\\S+").findAll(input.text).map { it.value }.distinct().toList()
        if (urls.isEmpty()) { status.text = "Paste at least one link"; return }
        if (!online()) { status.text = "No internet connection"; return }
        analyzeBtn.isEnabled = false
        analyzer.execute {
            val problems = mutableListOf<String>()
            var added = 0
            for ((n, u) in urls.withIndex()) {
                if (items.any { it.url == u }) continue
                runOnUiThread { status.text = "Checking link ${n + 1} of ${urls.size}..." }
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
                    runOnUiThread { addRow(item); persist() }
                    added++
                } catch (e: Exception) {
                    problems.add("Link ${n + 1}: " + why(e).first)
                }
            }
            runOnUiThread {
                analyzeBtn.isEnabled = true
                val head = if (added > 0) "Tick the videos, pick a resolution, then tap Download." else ""
                status.text = (listOf(head) + problems).filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
    }

    private fun addRow(a: Item) {
        a.check = CheckBox(this)
        a.spinner = Spinner(this)
        a.spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, a.labels)
        a.bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        a.state = TextView(this).apply { text = "Waiting" }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(content, TextView(this).apply { text = a.title; maxLines = 2; textSize = 16f })
        add(content, a.spinner)
        add(content, a.bar)
        add(content, a.state)
        a.toggle = btn("Start") { if (a.busy && !a.paused && !a.cancelled) pauseItem(a) else startItem(a) }
        a.stopBtn = btn("Stop") { stopItem(a) }
        add(content, row(a.toggle, a.stopBtn))
        a.box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(12), 0, px(4))
            addView(a.check, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (a.done) {
            a.bar.progress = 100
            a.state.text = "Done - saved"
        }
        list.addView(a.box)
        items.add(a)
        refreshButtons(a)
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in items) arr.put(JSONObject().apply {
            put("url", a.url); put("title", a.title)
            put("labels", JSONArray(a.labels)); put("choices", JSONArray(a.choices))
            put("sel", a.spinner.selectedItemPosition); put("done", a.done)
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
                o.optJSONArray("files")?.let { f -> for (k in 0 until f.length()) a.files.add(f.getString(k)) }
                addRow(a)
                a.spinner.setSelection(o.optInt("sel"))
                if (!a.done) a.state.text = "Paused - tap Start to continue"
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

    private fun removeAll() {
        if (items.isEmpty()) return
        confirm("Remove every item from the list, including unfinished ones? Partial downloads are discarded. Saved files are kept.") {
            for (a in items) {
                a.cancelled = true
                if (a.busy) { try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {} }
                else dirFor(a, choiceOf(a)).deleteRecursively()
            }
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

    // ---------- downloading ----------
    private fun downloadSelected() {
        if (items.isEmpty()) { status.text = "Tap \"Check links\" first"; return }
        val sel = items.filter { it.check.isChecked }
        if (sel.isEmpty()) { status.text = "Tick the videos you want to download"; return }
        sel.forEach { startItem(it) }
    }

    private fun startItem(a: Item) {
        if (a.busy || a.done) return
        val choice = choiceOf(a)
        val dir = dirFor(a, choice).apply { mkdirs() }
        if (!running.add(dir.name)) { a.state.text = "Already running in the background"; return }
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
        try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
        refreshButtons(a)
    }

    /** Stop discards the partial file; Start then begins from zero. */
    private fun stopItem(a: Item) {
        if (a.done) return
        if (a.busy) {
            a.cancelled = true
            a.state.text = "Stopping..."
            try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
        } else {
            dirFor(a, choiceOf(a)).deleteRecursively()
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

    private fun ui(a: Item, text: String) = runOnUiThread { a.state.text = text }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)

    private fun buildRequest(url: String, choice: Int, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--continue")
        r.addOption("--retries", "3")
        r.addOption("--fragment-retries", "3")
        r.addOption("--retry-sleep", "2")
        r.addOption("--socket-timeout", "20")
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

    private fun work(a: Item, choice: Int, dir: File) {
        var tries = 0
        try {
            while (true) {
                if (a.cancelled) return
                if (a.paused) { ui(a, "Paused - tap Start to continue"); return }
                if (!online()) {
                    ui(a, "Waiting for internet - will continue automatically")
                    while (!online() && !a.cancelled && !a.paused) Thread.sleep(1500)
                    continue
                }
                try {
                    a.pid = dir.name + "-" + System.nanoTime()
                    YoutubeDL.getInstance().execute(buildRequest(a.url, choice, dir), a.pid) { pr, eta, line ->
                        if (a.paused || a.cancelled) {
                            try { YoutubeDL.getInstance().destroyProcessById(a.pid) } catch (_: Exception) {}
                            return@execute
                        }
                        if (pr >= 0) tries = 0
                        val text: String? = when {
                            line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                                line.contains("[VideoConvertor]") -> "Merging / converting..."
                            pr >= 0 -> {
                                val speed = Regex("at\\s+(\\S+/s)").find(line)?.groupValues?.get(1)
                                val etaS = if (eta > 0) fmt(eta) else Regex("ETA\\s+(\\S+)").find(line)?.groupValues?.get(1)
                                "${pr.toInt()}%" + (if (speed != null) " | $speed" else "") +
                                    (if (etaS != null) " | ETA $etaS" else "")
                            }
                            else -> null
                        }
                        if (text != null) runOnUiThread {
                            if (pr >= 0) a.bar.progress = pr.toInt()
                            a.state.text = text
                        }
                    }
                    val files = dir.listFiles().orEmpty()
                        .filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
                    if (files.isEmpty()) throw Exception("incomplete download")
                    val saved = files.map { save(it) }
                    dir.deleteRecursively()
                    a.files.addAll(saved)
                    a.done = true
                    runOnUiThread { a.bar.progress = 100; a.state.text = "Done - saved"; persist() }
                    return
                } catch (e: Exception) {
                    if (a.cancelled) return
                    if (a.paused) { ui(a, "Paused - tap Start to continue"); return }
                    val (msg, net) = why(e)
                    if (net && tries < 30) {
                        tries++
                        ui(a, "$msg - retrying...")
                        if (online()) Thread.sleep(3000)
                        continue
                    }
                    ui(a, "$msg. Tap Download to retry - progress is kept.")
                    return
                }
            }
        } finally {
            a.busy = false
            running.remove(dir.name)
            if (a.cancelled) dir.deleteRecursively()
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
