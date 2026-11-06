package com.example.vidgrab

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var audio: CheckBox
    private lateinit var go: Button
    private lateinit var upd: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 3, p, p)
        }
        val title = TextView(this).apply { text = "VidGrab"; textSize = 28f }
        input = EditText(this).apply { hint = "Paste video link"; setSingleLine() }
        audio = CheckBox(this).apply { text = "Audio only (MP3)" }
        go = Button(this).apply { text = "Download"; isEnabled = false; setOnClickListener { start() } }
        upd = Button(this).apply { text = "Update engine"; isEnabled = false; setOnClickListener { update() } }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status = TextView(this).apply { text = "Starting engine..." }
        for (v in listOf(title, input, audio, go, upd, bar, status)) {
            root.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        handleIntent(intent)
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                runOnUiThread {
                    go.isEnabled = true
                    upd.isEnabled = true
                    status.text = "Ready (engine ${YoutubeDL.getInstance().versionName(application)}). Paste a link or share one to VidGrab."
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
            val url = Regex("https?://\\S+").find(t)?.value ?: t
            input.setText(url)
        }
    }

    private fun start() {
        val url = input.text.toString().trim()
        if (!url.startsWith("http")) { status.text = "Paste a valid link"; return }
        go.isEnabled = false
        bar.progress = 0
        status.text = "Starting..."
        val audioOnly = audio.isChecked
        Thread {
            try {
                val tmp = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
                val req = YoutubeDLRequest(url)
                req.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
                req.addOption("--no-mtime")
                if (audioOnly) {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                } else {
                    req.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
                    req.addOption("--merge-output-format", "mp4")
                }
                YoutubeDL.getInstance().execute(req) { pr, _, line ->
                    runOnUiThread {
                        if (pr >= 0) bar.progress = pr.toInt()
                        status.text = line
                    }
                }
                val files = tmp.listFiles().orEmpty()
                    .filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                files.forEach { save(it) }
                runOnUiThread {
                    bar.progress = 100
                    status.text = "Saved ${files.size} file(s) to Downloads/VidGrab"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed: ${e.message}" }
            } finally {
                runOnUiThread { go.isEnabled = true }
            }
        }.start()
    }

    private fun update() {
        go.isEnabled = false
        upd.isEnabled = false
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
                runOnUiThread { go.isEnabled = true; upd.isEnabled = true }
            }
        }.start()
    }

    private fun save(f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension)
            ?: "application/octet-stream"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VidGrab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        f.delete()
    }
}
