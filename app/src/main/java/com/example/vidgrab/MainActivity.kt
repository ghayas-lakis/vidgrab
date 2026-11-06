package com.example.vidgrab

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // choices: height in pixels, 0 = best available, -1 = audio only (MP3)
    class Item(val url: String, val title: String, val labels: List<String>, val choices: List<Int>) {
        lateinit var spinner: Spinner
        lateinit var bar: ProgressBar
        lateinit var state: TextView
        lateinit var box: LinearLayout
        var busy = false
        var done = false
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
    private val items = mutableListOf<Item>()
    private var ready = false
    private var dp = 1f

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
        analyzeBtn = btn("1. Check links") { analyze() }
        analyzeBtn.isEnabled = false
        updBtn = btn("Update engine") { update() }
        updBtn.isEnabled = false
        add(root, row(analyzeBtn, btn("Clear list") { clearList() }))
        folderBtn = btn("") { pickFolder() }
        add(root, folderBtn)
        updateFolderLabel()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, list)
        add(root, btn("2. Download all") { downloadAll() })
        add(root, updBtn)
        status = TextView(this).apply { text = "Starting engine..." }
        add(root, status)
        setContentView(ScrollView(this).apply { addView(root) })
        handleIntent(intent)
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                ready = true
                runOnUiThread {
                    analyzeBtn.isEnabled = true
                    updBtn.isEnabled = true
                    status.text = "Ready (engine ${YoutubeDL.getInstance().versionName(application)})."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Engine failed: ${e.message}" }
            }
        }.start()
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

    // ---------- step 1: list the available resolutions ----------
    private fun analyze() {
        val urls = Regex("https?://\\S+").findAll(input.text).map { it.value }.distinct().toList()
        if (urls.isEmpty()) { status.text = "Paste at least one link"; return }
        analyzeBtn.isEnabled = false
        analyzer.execute {
            for ((n, u) in urls.withIndex()) {
                runOnUiThread { status.text = "Checking link ${n + 1} of ${urls.size}..." }
                try {
                    val req = YoutubeDLRequest(u)
                    req.addOption("--dump-single-json")
                    req.addOption("--no-playlist")
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
                    runOnUiThread { addRow(item) }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Could not read: $u\n${e.message}" }
                }
            }
            runOnUiThread {
                analyzeBtn.isEnabled = true
                if (items.isNotEmpty()) status.text = "Pick a resolution for each video, then tap Download all."
            }
        }
    }

    private fun addRow(a: Item) {
        a.box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12), 0, px(4))
        }
        add(a.box, TextView(this).apply { text = a.title; maxLines = 2; textSize = 16f })
        a.spinner = Spinner(this)
        a.spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, a.labels)
        add(a.box, a.spinner)
        a.bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        add(a.box, a.bar)
        a.state = TextView(this).apply { text = "Waiting" }
        add(a.box, a.state)
        list.addView(a.box)
        items.add(a)
    }

    private fun clearList() {
        items.clear()
        list.removeAllViews()
        status.text = "List cleared."
    }

    // ---------- step 2: download (2 at a time) ----------
    private fun downloadAll() {
        if (items.isEmpty()) { status.text = "Tap \"Check links\" first"; return }
        items.forEach { startItem(it) }
    }

    private fun startItem(a: Item) {
        if (a.busy || a.done) return
        a.busy = true
        a.spinner.isEnabled = false
        a.state.text = "Queued..."
        val choice = a.choices[a.spinner.selectedItemPosition]
        downloader.execute {
            val tmp = File(cacheDir, "dl/${System.nanoTime()}").apply { mkdirs() }
            try {
                val req = YoutubeDLRequest(a.url)
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
                when {
                    choice == -1 -> {
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                    }
                    choice == 0 -> {
                        req.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                        req.addOption("--merge-output-format", "mp4")
                    }
                    else -> {
                        req.addOption("-f",
                            "bv*[height<=$choice][ext=mp4]+ba[ext=m4a]/bv*[height<=$choice]+ba/b[height<=$choice]")
                        req.addOption("--merge-output-format", "mp4")
                    }
                }
                YoutubeDL.getInstance().execute(req, tmp.name) { pr, _, _ ->
                    runOnUiThread {
                        if (pr >= 0) { a.bar.progress = pr.toInt(); a.state.text = "Downloading ${pr.toInt()}%" }
                    }
                }
                val files = tmp.listFiles().orEmpty()
                    .filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                files.forEach { save(it) }
                a.done = true
                runOnUiThread { a.bar.progress = 100; a.state.text = "Done - saved ${files.size} file(s)" }
            } catch (e: Exception) {
                runOnUiThread { a.state.text = "Failed: ${e.message}" }
            } finally {
                tmp.deleteRecursively()
                a.busy = false
                runOnUiThread { a.spinner.isEnabled = !a.done }
            }
        }
    }

    private fun save(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension)
            ?: "application/octet-stream"
        val tree = prefs.getString("tree", null)
        val uri: Uri = if (tree != null) {
            val t = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            DocumentsContract.createDocument(contentResolver, parent, mime, f.name)!!
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        }
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    // ---------- engine update ----------
    private fun update() {
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
                runOnUiThread { status.text = "Update failed: ${e.message}" }
            } finally {
                runOnUiThread { updBtn.isEnabled = true }
            }
        }.start()
    }
}
