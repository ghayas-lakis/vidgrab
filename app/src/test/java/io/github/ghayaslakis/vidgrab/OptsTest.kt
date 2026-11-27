package io.github.ghayaslakis.vidgrab

import android.content.SharedPreferences
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The yt-dlp arguments built from Settings > More options (pure logic: runs on the computer, no phone). */
class OptsTest {
    /** Settings held in a map. */
    private class Prefs(vararg v: Pair<String, Any>) : SharedPreferences {
        val m = mutableMapOf(*v)
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String, d: String?) = m[k] as String? ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = m[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = m[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = m[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = m[k] as Boolean? ?: d
        override fun contains(k: String) = k in m
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun args(p: SharedPreferences, choice: Int): List<String> {
        val r = YoutubeDLRequest("https://example.com/v")
        Opts.download(r, p, choice)
        return r.buildCommand()
    }

    /** The value after an option, or null when the option is missing. */
    private fun List<String>.after(o: String) = indexOf(o).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test fun defaultsKeepTheOldFormatsAndAddMetadata() {
        val a = args(Prefs(), 720)
        assertEquals("bv*[height<=720][ext=mp4]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]", a.after("-f"))
        assertEquals("mp4", a.after("--merge-output-format"))
        assertTrue("--embed-metadata" in a)
        assertFalse("subtitles are off by default", "--write-subs" in a)
        assertFalse("no thumbnail in videos by default", "--embed-thumbnail" in a)
        assertFalse("-S" in a)
        assertFalse("--limit-rate" in a)
        assertEquals("bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b", args(Prefs(), 0).after("-f"))
    }

    @Test fun audioFormatsQualityAndCoverArt() {
        val mp3 = args(Prefs(), -1)
        assertTrue("-x" in mp3)
        assertEquals("mp3", mp3.after("--audio-format"))
        assertNull("standard quality adds nothing", mp3.after("--audio-quality"))
        assertTrue("cover art in MP3 by default", "--embed-thumbnail" in mp3)
        assertFalse("audio has no video format", "-f" in mp3)

        val opus = args(Prefs("audioFmt" to "opus", "audioQ" to "192K"), -1)
        assertEquals("opus", opus.after("--audio-format"))
        assertEquals("192K", opus.after("--audio-quality"))
        assertFalse("ffmpeg can't embed cover art in Opus", "--embed-thumbnail" in opus)

        val flac = args(Prefs("audioFmt" to "flac", "audioQ" to "320K"), -1)
        assertNull("lossless has no bitrate", flac.after("--audio-quality"))
        assertEquals("Audio only (FLAC)", Opts.audioLabel(Prefs("audioFmt" to "flac")))
        assertEquals("Audio only (MP3)", Opts.audioLabel(Prefs()))
    }

    @Test fun containerCodecSubtitlesSponsorBlockAndLimits() {
        val a = args(Prefs("container" to "mkv", "h264" to true, "subs" to true, "subLangs" to "fr.*",
            "sponsor" to "remove", "rate" to "2M", "thumbVideo" to true), 1080)
        assertEquals("bv*[height<=1080]+ba/b[height<=1080]", a.after("-f"))
        assertEquals("mkv", a.after("--merge-output-format"))
        assertEquals("vcodec:h264,res,acodec:m4a", a.after("-S"))
        assertTrue("--write-subs" in a && "--write-auto-subs" in a && "--embed-subs" in a)
        assertEquals("fr.*", a.after("--sub-langs"))
        assertEquals("sponsor,selfpromo,interaction", a.after("--sponsorblock-remove"))
        assertEquals("2M", a.after("--limit-rate"))
        assertTrue("--embed-thumbnail" in a)

        val sep = args(Prefs("subs" to true, "subsEmbed" to false, "subsAuto" to false, "sponsor" to "mark"), 0)
        assertEquals("srt", sep.after("--convert-subs"))
        assertFalse("--embed-subs" in sep || "--write-auto-subs" in sep)
        assertEquals("all", sep.after("--sponsorblock-mark"))
    }

    @Test fun extraOptionsAndSections() {
        val a = args(Prefs("extraArgs" to "--no-mtime --parse-metadata \"title:%(artist)s - %(title)s\""), 0)
        assertTrue("--no-mtime" in a)
        assertEquals("title:%(artist)s - %(title)s", a.after("--parse-metadata"))
        assertEquals("the link stays last", "https://example.com/v", a.last())

        val r = YoutubeDLRequest("u")
        Opts.section(r, 90, -1, true)
        val s = r.buildCommand()
        assertEquals("*90-inf", s.after("--download-sections"))
        assertTrue("--force-keyframes-at-cuts" in s)
    }

    @Test fun typedArgumentsTimesAndNames() {
        assertEquals(listOf("-a", "b c", "", "d"), Opts.splitArgs("  -a 'b c' \"\"  d "))
        assertEquals(emptyList<String>(), Opts.splitArgs("   "))
        assertEquals(90L, Opts.parseTime("1:30"))
        assertEquals(3723L, Opts.parseTime("1:02:03"))
        assertEquals(45L, Opts.parseTime(" 45 "))
        assertNull(Opts.parseTime("1:"))
        assertNull(Opts.parseTime("a:10"))
        assertNull(Opts.parseTime("1:2:3:4"))
        assertEquals("1:05:00", Opts.clock(3900))
        assertEquals("2:05", Opts.clock(125))
        assertEquals("%(uploader)s_%(title)s", Opts.cleanTemplate("%(uploader)s/%(title)s.%(ext)s"))
        assertEquals(Opts.TITLE_TPL, Opts.cleanTemplate("  "))
    }
}
