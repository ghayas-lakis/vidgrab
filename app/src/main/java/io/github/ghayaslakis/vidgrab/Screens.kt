package io.github.ghayaslakis.vidgrab

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/**
 * The full-page screens opened from the main screen: Settings > More options, and More > Download history.
 * They are built in code with the main screen's design helpers, like the rest of VidGrab.
 */

/** A full-screen page with a back arrow and a title; the caller adds the content (and head actions). */
internal class Page(val dialog: Dialog, val root: LinearLayout, val head: LinearLayout)

@Suppress("DEPRECATION")
internal fun MainActivity.fullPage(title: String, onClose: () -> Unit): Page {
    val pal = MainActivity.pal
    val d = Dialog(this, if (pal.dark) android.R.style.Theme_DeviceDefault_NoActionBar
        else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    val head = hrow()
    head.setPadding(px(S2), px(S2), px(S3), px(S2))
    val back = ImageButton(this).apply {
        setImageDrawable(Glyph(G_BACK, pal.text, px(22)))
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(null, oval(Color.WHITE))
        contentDescription = "Back"
        tooltipText = "Back"
        setOnClickListener { d.dismiss() }
    }
    head.addView(back, lp(px(44), px(44)))
    head.addView(label(title, T_DISPLAY - 2f, pal.text, BOLD).apply {
        setPadding(px(S2), 0, px(S2), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }, lp(0, WRAP, 1f))
    val root = LinearLayout(this)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(pal.bg)
    root.addView(head, lp(MATCH, WRAP))
    root.addView(View(this).apply { setBackgroundColor(pal.outline) }, lp(MATCH, 1))
    d.setContentView(root)
    d.window?.let { w ->
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundDrawable(ColorDrawable(pal.bg))
        w.statusBarColor = pal.bg
        w.navigationBarColor = pal.bg
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        w.decorView.systemUiVisibility = if (pal.dark) 0 else light
        w.setWindowAnimations(android.R.style.Animation_Activity)
    }
    d.setOnDismissListener { onClose() }
    return Page(d, root, head)
}

/** A chevron for rows that open a choice. */
private fun MainActivity.chevron() = ImageView(this).apply {
    setImageDrawable(Glyph(G_CHEVRON_RIGHT, MainActivity.pal.muted, px(18)))
}

// ---------- Settings > More options ----------
internal fun MainActivity.showOptions() {
    val pal = MainActivity.pal
    val p = prefs
    var cards = false          // the quality pickers show the audio format: rebuild the cards
    var theme = false
    val page = fullPage("More options") {
        when {
            theme -> applyTheme()
            cards -> { reattach(); qualityLabel.text = qualityName(p.getInt("quality", 0)) }
        }
    }
    val body = LinearLayout(this)
    body.orientation = LinearLayout.VERTICAL
    body.setPadding(px(S4), 0, px(S4), px(S5 * 2))
    val sc = ScrollView(this)
    sc.isVerticalScrollBarEnabled = false
    sc.addView(body)
    page.root.addView(sc, lp(MATCH, 0, 1f))

    fun section(t: String, note: String = ""): LinearLayout {
        add(body, overline(t))
        if (note.isNotEmpty()) add(body, label(note, T_CAPTION, pal.muted).apply {
            setPadding(px(S1), 0, px(S1), px(S2))
            setLineSpacing(px(2).toFloat(), 1f)
        })
        val c = card()
        c.setPadding(px(S1), px(S1), px(S1), px(S1))
        add(body, c)
        return c
    }

    fun row(c: LinearLayout, r: View) {
        if (c.childCount > 0) addDivider(c, 60)
        add(c, r)
    }

    fun toggle(c: LinearLayout, icon: Int, title: String, sub: String, key: String, def: Boolean,
               changed: (Boolean) -> Unit = {}) {
        val s = switchOf(p.getBoolean(key, def)) { v -> p.edit().putBoolean(key, v).apply(); changed(v) }
        val r = settingRow(icon, title, subText(sub), s)
        r.setOnClickListener { s.toggle() }
        row(c, r)
    }

    fun choice(c: LinearLayout, icon: Int, title: String, key: String, def: String, list: List<Pair<String, String>>,
               changed: () -> Unit = {}) {
        val sub = subText(Opts.nameOf(list, p.getString(key, def)!!) ?: list[0].second)
        val r = settingRow(icon, title, sub, chevron())
        r.setOnClickListener {
            val cur = list.indexOfFirst { it.first == p.getString(key, def) }.coerceAtLeast(0)
            AlertDialog.Builder(this, dialogTheme()).setTitle(title)
                .setSingleChoiceItems(list.map { it.second }.toTypedArray(), cur) { dlg, k ->
                    p.edit().putString(key, list[k].first).apply()
                    sub.text = list[k].second
                    dlg.dismiss()
                    changed()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        row(c, r)
    }

    /** A typed value. check returns an error to show, or null when the text is fine. */
    fun typed(c: LinearLayout, icon: Int, title: String, key: String, hint: String, explain: String,
              shown: (String) -> String, check: (String) -> String? = { null }) {
        val sub = subText(shown(p.getString(key, "")!!)).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val r = settingRow(icon, title, sub, chevron())
        r.setOnClickListener {
            val e = EditText(this).apply {
                this.hint = hint
                setText(p.getString(key, ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(px(S5 + S1), px(S2), px(S5 + S1), 0)
            box.addView(label(explain, T_LABEL, pal.muted).apply { setLineSpacing(px(2).toFloat(), 1f) })
            box.addView(e, lp(MATCH, WRAP).apply { topMargin = px(S3) })
            val dlg = AlertDialog.Builder(this, dialogTheme()).setTitle(title).setView(box)
                .setPositiveButton("Save", null)
                .setNeutralButton("Reset") { _, _ -> p.edit().remove(key).apply(); sub.text = shown("") }
                .setNegativeButton("Cancel", null)
                .show()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener save@{
                val t = e.text.toString().trim()
                val err = if (t.isEmpty()) null else check(t)
                if (err != null) { e.error = err; return@save }
                p.edit().putString(key, t).apply()
                sub.text = shown(t)
                dlg.dismiss()
            }
        }
        row(c, r)
    }

    fun action(c: LinearLayout, icon: Int, title: String, sub: String, act: () -> Unit) {
        val r = settingRow(icon, title, subText(sub), chevron())
        r.setOnClickListener { act() }
        row(c, r)
    }

    // ----- video -----
    val video = section("Video")
    choice(video, G_VIDEO, "Container", "container", "mp4", Opts.CONTAINERS)
    toggle(video, G_SPARK, "Prefer H.264", "Plays on every phone, TV and editor. On YouTube it can mean 1080p at most",
        "h264", false)
    toggle(video, G_INFO, "Thumbnail in video files", "Shown as the video's picture in some players", "thumbVideo", false)

    // ----- audio -----
    val audio = section("Audio only")
    choice(audio, G_VIDEO, "Format", "audioFmt", "mp3", Opts.AUDIO_FORMATS) { cards = true }
    choice(audio, G_SPARK, "Quality", "audioQ", "", Opts.AUDIO_QUALITY)
    toggle(audio, G_INFO, "Cover art", "The video's picture inside MP3 and M4A files", "coverArt", true)

    // ----- subtitles -----
    val subs = section("Subtitles")
    toggle(subs, G_FILE, "Download subtitles", "When the video has them, in the languages below", "subs", false)
    typed(subs, G_GLOBE, "Languages", "subLangs", Opts.subLangsDefault(),
        "Language codes separated by commas. \"en.*\" means every kind of English (en, en-US, ...); \"all\" means " +
            "every language. Leave empty for your phone's language and English.",
        { it.ifEmpty { Opts.subLangsDefault() + " (default)" } },
        { t -> if (Regex("^[\\w.*,+-]+$").matches(t.replace(" ", ""))) null else "Use codes like en, fr, ar.* separated by commas" })
    toggle(subs, G_SPARK, "Include automatic captions", "Made by the site (for example YouTube's), when there are no others",
        "subsAuto", true)
    toggle(subs, G_VIDEO, "Inside the video", "Off: saved next to the video as .srt files", "subsEmbed", true)

    // ----- the file -----
    val file = section("Files")
    toggle(file, G_INFO, "Title, artist, date and chapters", "Written into the file, so players and music apps show them",
        "embedMeta", true)
    val names = Opts.NAMES + ("custom" to "Custom...")
    val curName = { Opts.nameOf(Opts.NAMES, Opts.nameTemplate(p)) ?: ("Custom: " + Opts.nameTemplate(p)) }
    val nameSub = subText(curName())
    val nameRow = settingRow(G_FILE, "File names", nameSub, chevron())
    nameRow.setOnClickListener {
        val cur = names.indexOfFirst { it.first == Opts.nameTemplate(p) }.let { if (it < 0) names.size - 1 else it }
        AlertDialog.Builder(this, dialogTheme()).setTitle("File names")
            .setSingleChoiceItems(names.map { it.second }.toTypedArray(), cur) { dlg, k ->
                dlg.dismiss()
                if (names[k].first != "custom") {
                    p.edit().putString("nameTpl", names[k].first).apply()
                    nameSub.text = curName()
                    return@setSingleChoiceItems
                }
                val e = EditText(this).apply {
                    setText(Opts.nameTemplate(p))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(px(S5 + S1), px(S2), px(S5 + S1), 0)
                box.addView(label("A yt-dlp output template, without the extension. For example " +
                    "%(uploader)s - %(title)s, or %(playlist_index)s. %(title)s. Folders are not allowed.",
                    T_LABEL, pal.muted).apply { setLineSpacing(px(2).toFloat(), 1f) })
                box.addView(e, lp(MATCH, WRAP).apply { topMargin = px(S3) })
                val d2 = AlertDialog.Builder(this, dialogTheme()).setTitle("Custom file names").setView(box)
                    .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show()
                d2.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener save@{
                    val t = e.text.toString()
                    if (!t.contains("%(")) { e.error = "Use at least one field, such as %(title)s"; return@save }
                    p.edit().putString("nameTpl", Opts.cleanTemplate(t)).apply()
                    nameSub.text = curName()
                    d2.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    row(file, nameRow)

    // ----- YouTube -----
    val yt = section("YouTube", "SponsorBlock is a free community database of sponsor segments in YouTube videos.")
    choice(yt, G_CUT, "SponsorBlock", "sponsor", "off", Opts.SPONSOR)

    // ----- downloading -----
    val dl = section("Downloading")
    toggle(dl, G_DOWNLOAD, "Start downloads right away", "Each link starts downloading as soon as it is checked",
        "autoStart", false)
    choice(dl, G_WIFI, "Speed limit", "rate", "", Opts.RATES)
    toggle(dl, G_GLOBE, "Use IPv4 only", "Can help when a site or network has trouble with IPv6", "ipv4", false)

    // ----- engine -----
    val eng = section("Download engine (yt-dlp)")
    toggle(eng, G_REFRESH, "Update once a day", "Sites change often; an old engine stops working with them",
        "autoUpdate", true)
    choice(eng, G_REFRESH, "Update channel", "channel", "nightly", Opts.CHANNELS)
    typed(eng, G_TUNE, "Extra yt-dlp options", "extraArgs", "--embed-chapters --no-mtime",
        "Added to every download, as on yt-dlp's command line. Options that are wrong make downloads fail, so " +
            "leave this empty if unsure. VidGrab chooses where files go (use File names instead).",
        { it.ifEmpty { "None" } },
        { t ->
            val bad = Opts.splitArgs(t).firstOrNull { it in listOf("-o", "--output", "-P", "--paths", "--progress-template",
                "-q", "--quiet", "--batch-file", "-a", "--exec") || it.startsWith("--output=") || it.startsWith("--paths=") }
            when {
                bad != null -> "VidGrab needs to control $bad itself"
                Opts.splitArgs(t).none { it.startsWith("-") } -> "Options start with - or --"
                else -> null
            }
        })

    // ----- app -----
    val app = section("App")
    choice(app, G_SPARK, "Theme", "theme", "system", Opts.THEMES) { theme = true }
    toggle(app, G_HISTORY, "Incognito", "Don't add downloads to the history. Downloads still work as usual",
        "incognito", false)
    action(app, G_DOWNLOAD, "Back up settings and lists",
        "Your settings, list and download history in one file. Logins are not included") {
        page.dialog.dismiss(); pickBackupTarget()
    }
    action(app, G_FOLDER, "Restore from a backup", "Adds its videos and history; its settings replace these") {
        page.dialog.dismiss(); pickBackupSource()
    }

    page.dialog.show()
}

// ---------- More > Download history ----------
internal fun MainActivity.showHistory() {
    val pal = MainActivity.pal
    val page = fullPage("Download history") {}
    var all = History.all(this)
    var shown = all
    val clear = btn("Clear", GHOST, G_CLEAR_ALL, 36) {}
    clear.textSize = T_LABEL
    clear.setPadding(px(10), 0, px(12), 0)
    page.head.addView(clear, lp(WRAP, px(36)))

    val filter = EditText(this).apply {
        hint = "Search the history"
        setSingleLine()
        textSize = T_BODY
        background = rounded(pal.surface2, R_PILL, pal.outline)
        setPadding(px(S4), px(10), px(S4), px(10))
        setTextColor(pal.text)
        setHintTextColor(pal.subtle)
        setCompoundDrawablesRelative(Glyph(G_SEARCH, pal.subtle).apply { setBounds(0, 0, px(18), px(18)) }, null, null, null)
        compoundDrawablePadding = px(S2)
    }
    page.root.addView(filter, lp(MATCH, WRAP).apply { setMargins(px(S4), px(S3), px(S4), px(S2)) })
    val empty = label("", T_BODY, pal.muted).apply {
        gravity = Gravity.CENTER
        setPadding(px(S5 * 2), px(S5 * 2), px(S5 * 2), px(S5 * 2))
        setLineSpacing(px(2).toFloat(), 1f)
    }
    page.root.addView(empty, lp(MATCH, WRAP))
    val listView = ListView(this)
    listView.divider = ColorDrawable(pal.outline)
    listView.dividerHeight = 1
    listView.selector = ripple(null, ColorDrawable(Color.WHITE))
    listView.setBackgroundColor(pal.bg)
    page.root.addView(listView, lp(MATCH, 0, 1f))

    val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, old: View?, parent: ViewGroup): View {
            val e = shown[i]
            val r = hrow()
            r.setPadding(px(S4), px(S3), px(S4), px(S3))
            r.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            r.addView(tile(G_VIDEO, pal.accent, pal.accentSoft, 40, R_MD - 2), lp(px(40), px(40)))
            val t = LinearLayout(this@showHistory)
            t.orientation = LinearLayout.VERTICAL
            t.setPadding(px(S3), 0, 0, 0)
            t.addView(label(e.title, T_BODY, pal.text, MEDIUM).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            val meta = listOf(day(e.date), e.meta, if (e.files.size > 1) "${e.files.size} files" else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            t.addView(subText(meta).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            r.addView(t, lp(0, WRAP, 1f))
            return r
        }
    }
    listView.adapter = adapter

    fun refresh() {
        val q = filter.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) all else all.filter { q in it.title.lowercase() || q in it.url.lowercase() || q in it.meta.lowercase() }
        adapter.notifyDataSetChanged()
        clear.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        filter.visibility = if (all.size > 6) View.VISIBLE else View.GONE
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            all.isNotEmpty() -> "Nothing in the history matches \"$q\"."
            prefs.getBoolean("incognito", false) -> "Incognito is on (Settings > More options), so downloads are not added here."
            else -> "Nothing here yet. Every video you download is listed here, to open, share or download again later."
        }
    }
    fun reload() { all = History.all(this); refresh() }

    filter.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = refresh()
    })
    clear.setOnClickListener {
        confirm("Clear the whole download history? The downloaded files are kept.") {
            History.clear(this)
            reload()
            tell("History cleared. The files are kept.")
        }
    }
    listView.setOnItemClickListener { _, _, i, _ ->
        val e = shown.getOrNull(i) ?: return@setOnItemClickListener
        val acts = mutableListOf<Pair<String, () -> Unit>>()
        if (e.files.isNotEmpty()) {
            acts.add("Open" to {
                val u = Uri.parse(e.files[0])
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u) ?: "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { tell("That file is gone (deleted or moved), or no app can open it.") }
            })
            acts.add("Share" to {
                val uris = ArrayList(e.files.map { Uri.parse(it) })
                val s = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                s.type = (try { contentResolver.getType(uris[0]) } catch (_: Exception) { null }) ?: "video/*"
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(Intent.createChooser(s, "Share")) } catch (_: Exception) { tell("That file is gone.") }
            })
        }
        if (e.url.startsWith("http")) {
            acts.add("Download again" to {
                val inList = MainActivity.items.firstOrNull { it.url == e.url }
                when {
                    inList != null && inList.done -> tell("It is still in your list, already downloaded. Remove it from " +
                        "the list first (More > Remove completed) to download it again.")
                    inList != null -> tell("It is already in your list.")
                    else -> {
                        page.dialog.dismiss()
                        input.append(e.url + "\n")
                        tell("Added the link again. It is checked, then shows up in your list.")
                    }
                }
            })
            acts.add("Copy link" to {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Link", e.url))
                tell("Link copied.")
            })
        }
        acts.add("Remove from the history" to { History.remove(this, e); reload() })
        if (e.files.isNotEmpty()) acts.add("Delete the file and remove it" to {
            confirm("Delete " + (if (e.files.size > 1) "these ${e.files.size} files" else "this file") +
                " from your phone and remove it from the history?") {
                val failed = e.files.count { !deleteSaved(it) }
                History.remove(this, e)
                reload()
                tell(if (failed == 0) "Deleted." else "Removed from the history, but $failed file(s) could not be " +
                    "deleted (VidGrab may no longer own them after a reinstall). Delete them in your Files app.")
            }
        })
        AlertDialog.Builder(this, dialogTheme()).setTitle(e.title.take(80))
            .setItems(acts.map { it.first }.toTypedArray()) { _, k -> acts[k].second() }
            .setNegativeButton("Close", null)
            .show()
    }
    refresh()
    page.dialog.show()
}
