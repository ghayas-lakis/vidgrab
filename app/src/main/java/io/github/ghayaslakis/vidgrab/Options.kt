package io.github.ghayaslakis.vidgrab

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.Locale

/**
 * The download options of Settings > More options, and the yt-dlp arguments they turn into.
 * Kept apart from the screens so the arguments can be read (and tested) in one place.
 */
internal object Opts {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("vidgrab", Context.MODE_PRIVATE)

    // ---------- choices (value stored in the settings, name shown) ----------
    val CONTAINERS = listOf("mp4" to "MP4 (plays everywhere)", "mkv" to "MKV (keeps every format and subtitle)")
    val AUDIO_FORMATS = listOf("mp3" to "MP3", "m4a" to "M4A (AAC)", "opus" to "Opus", "flac" to "FLAC (lossless)",
        "wav" to "WAV (uncompressed)")
    val AUDIO_QUALITY = listOf("" to "Standard", "0" to "Best", "320K" to "320 kbps", "192K" to "192 kbps",
        "128K" to "128 kbps")
    val SPONSOR = listOf("off" to "Off", "mark" to "Mark segments as chapters",
        "remove" to "Cut out sponsors, self-promotion and subscribe reminders")
    val RATES = listOf("" to "No limit", "500K" to "500 KB/s", "1M" to "1 MB/s", "2M" to "2 MB/s", "5M" to "5 MB/s")
    val CHANNELS = listOf("nightly" to "Nightly (newest fixes, recommended)", "stable" to "Stable (fewer changes)",
        "master" to "Master (every change, for testing)")
    val THEMES = listOf("system" to "Follow the system", "light" to "Light", "dark" to "Dark")
    const val TITLE_TPL = "%(title).80s"
    val NAMES = listOf(
        TITLE_TPL to "Title",
        "%(uploader).30s - %(title).80s" to "Uploader - Title",
        "%(upload_date>%Y-%m-%d)s - %(title).80s" to "Date - Title",
        "%(title).80s [%(id)s]" to "Title [video id]",
    )
    /** Search engines yt-dlp knows: prefix, name. */
    val SEARCH = listOf("ytsearch30:" to "YouTube", "scsearch30:" to "SoundCloud")

    fun nameOf(list: List<Pair<String, String>>, v: String) = list.firstOrNull { it.first == v }?.second

    // ---------- values ----------
    fun container(p: SharedPreferences) = p.getString("container", "mp4")!!.takeIf { c -> CONTAINERS.any { it.first == c } } ?: "mp4"
    fun audioFormat(p: SharedPreferences) = p.getString("audioFmt", "mp3")!!.takeIf { c -> AUDIO_FORMATS.any { it.first == c } } ?: "mp3"
    fun audioName(p: SharedPreferences) = when (val f = audioFormat(p)) { "opus" -> "Opus"; else -> f.uppercase() }
    /** "Audio only (MP3)": the label of choice -1 in every quality picker. */
    fun audioLabel(p: SharedPreferences) = "Audio only (${audioName(p)})"
    fun subLangsDefault(): String {
        val l = Locale.getDefault().language.ifEmpty { "en" }
        return if (l == "en") "en.*" else "$l.*,en.*"
    }
    fun subLangs(p: SharedPreferences) = p.getString("subLangs", "")!!.trim().ifEmpty { subLangsDefault() }
    fun nameTemplate(p: SharedPreferences) = cleanTemplate(p.getString("nameTpl", TITLE_TPL)!!)
    fun channelName(p: SharedPreferences) = p.getString("channel", "nightly")!!

    fun channel(p: SharedPreferences): YoutubeDL.UpdateChannel = when (channelName(p)) {
        "stable" -> YoutubeDL.UpdateChannel.STABLE
        "master" -> YoutubeDL.UpdateChannel.MASTER
        else -> YoutubeDL.UpdateChannel.NIGHTLY
    }

    /** A file name template stays one file name: no folders, and the extension is added by VidGrab. */
    fun cleanTemplate(t: String): String {
        val s = t.trim().removeSuffix(".%(ext)s").replace('/', '_').replace('\\', '_')
        return s.ifEmpty { TITLE_TPL }
    }

    /** Dark or light screens: the setting, or the system's when it is "Follow the system". */
    fun dark(c: Context): Boolean = when (prefs(c).getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------- yt-dlp arguments ----------
    /** For link checks and downloads alike. */
    fun network(r: YoutubeDLRequest, p: SharedPreferences) {
        if (p.getBoolean("ipv4", false)) r.addOption("--force-ipv4")
    }

    /**
     * Format, conversion and post-processing options of a download.
     * choice: height in pixels, 0 = best available, -1 = audio only.
     */
    fun download(r: YoutubeDLRequest, p: SharedPreferences, choice: Int) {
        val audio = choice == -1
        if (audio) {
            val f = audioFormat(p)
            r.addOption("-x")
            r.addOption("--audio-format", f)
            val q = p.getString("audioQ", "")!!
            if (q.isNotEmpty() && f != "flac" && f != "wav") r.addOption("--audio-quality", q)
        } else {
            val mkv = container(p) == "mkv"
            val cap = if (choice > 0) "[height<=$choice]" else ""
            r.addOption("-f", if (mkv) "bv*$cap+ba/b$cap"
                else if (choice > 0) "bv*$cap[ext=mp4]+ba[ext=m4a]/bv*$cap+ba/b$cap"
                else "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            r.addOption("--merge-output-format", if (mkv) "mkv" else "mp4")
            // H.264 plays on every phone, TV and editor; it can mean a lower top resolution (YouTube: 1080p).
            if (p.getBoolean("h264", false)) r.addOption("-S", "vcodec:h264,res,acodec:m4a")
            if (p.getBoolean("subs", false)) {
                r.addOption("--write-subs")
                if (p.getBoolean("subsAuto", true)) r.addOption("--write-auto-subs")
                r.addOption("--sub-langs", subLangs(p))
                if (p.getBoolean("subsEmbed", true)) r.addOption("--embed-subs")
                else r.addOption("--convert-subs", "srt")
            }
        }
        if (p.getBoolean("embedMeta", true)) r.addOption("--embed-metadata")
        // Cover art: only for the formats ffmpeg itself can embed it in (the others need extra Python modules).
        val thumb = if (audio) p.getBoolean("coverArt", true) && audioFormat(p) in listOf("mp3", "m4a")
            else p.getBoolean("thumbVideo", false)
        if (thumb) {
            r.addOption("--embed-thumbnail")
            r.addOption("--convert-thumbnails", "jpg")
        }
        when (p.getString("sponsor", "off")) {
            "mark" -> { r.addOption("--sponsorblock-mark", "all"); r.addOption("--embed-chapters") }
            "remove" -> r.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction")
        }
        p.getString("rate", "")!!.takeIf { it.isNotEmpty() }?.let { r.addOption("--limit-rate", it) }
        val extra = splitArgs(p.getString("extraArgs", "")!!)
        if (extra.isNotEmpty()) r.addCommands(extra)
    }

    /** Download only part of the video: "*from-to" in seconds (to < 0 = until the end). */
    fun section(r: YoutubeDLRequest, from: Long, to: Long, exact: Boolean) {
        r.addOption("--download-sections", "*$from-" + if (to < 0) "inf" else "$to")
        if (exact) r.addOption("--force-keyframes-at-cuts")
    }

    /** Splits a typed command line into arguments: spaces separate them, quotes keep them together. */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var any = false
        for (ch in s) {
            when {
                quote != 0.toChar() -> if (ch == quote) quote = 0.toChar() else cur.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; any = true }
                ch.isWhitespace() -> { if (cur.isNotEmpty() || any) out.add(cur.toString()); cur.clear(); any = false }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty() || any) out.add(cur.toString())
        return out
    }

    // ---------- times typed by the user ----------
    /** "1:02:03", "2:30" or "95" as seconds; null when it isn't a time. */
    fun parseTime(t: String): Long? {
        val s = t.trim()
        if (s.isEmpty()) return null
        val parts = s.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 5 }) return null
        var v = 0L
        for (x in parts) v = v * 60 + x.toLong()
        return v
    }

    fun clock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
}
