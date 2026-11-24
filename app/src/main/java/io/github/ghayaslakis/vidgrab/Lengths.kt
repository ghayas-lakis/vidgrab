package io.github.ghayaslakis.vidgrab

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/** What is known about a video before downloading it (0 = unknown). */
internal class VideoInfo(val sec: Int, val bytes: Long = 0L, val parts: List<String> = emptyList())

/**
 * Finds out how long (and how big) videos are, for the browser's length and size filters, reading as little
 * as possible. Everything here uses the network: call it on a background thread. Never throws.
 */
internal class Lengths(private val ua: String, proxy: String) {
    private val proxyRule = proxy
    private val via: Proxy? = proxyOf(proxy)      // null = a kind of proxy only yt-dlp and the pages can use

    private fun proxyOf(p: String): Proxy? {
        if (p.isEmpty()) return Proxy.NO_PROXY
        val m = Regex("^(http|socks4|socks5)://([\\w.-]+):(\\d+)$").find(p) ?: return null
        val (kind, host, port) = m.destructured
        return Proxy(if (kind == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(host, port.toInt()))
    }

    private class Reply(val body: ByteArray, val total: Long, val url: String)

    /** Reads at most `max` bytes from position `from` (range = ask the server for just that part). */
    private fun get(url: String, from: Long, max: Int, range: Boolean, referer: String = "", cookie: String? = null,
                    until: ((ByteArrayOutputStream) -> Boolean)? = null): Reply? {
        val p = via ?: return null
        val c = URL(url).openConnection(p) as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", ua)
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            if (!cookie.isNullOrEmpty()) c.setRequestProperty("Cookie", cookie)
            if (range) {
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("Range", "bytes=$from-${from + max - 1}")
            }
            val code = c.responseCode
            if (code != 200 && code != 206) return null
            if (code == 200 && from > 0) return null        // the server can't send just a part
            val total = if (code == 206) c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
                else if (range) maxOf(0L, c.contentLengthLong) else 0L
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            c.inputStream.use { s ->
                while (out.size() < max) {
                    val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (until != null && until(out)) break
                }
            }
            return Reply(out.toByteArray(), total, c.url.toString())
        } catch (_: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }

    /** A video file or stream: an HLS or DASH playlist is read, an MP4 file's header is. */
    fun stream(url: String, referer: String, cookie: String?): VideoInfo? = try {
        val first = get(url, 0, CHUNK, true, referer, cookie)
        val start = first?.let { String(it.body, 0, minOf(it.body.size, 2048), Charsets.UTF_8) } ?: ""
        when {
            first == null -> null
            start.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U") -> hls(first.url, referer, cookie)
            start.contains("<MPD") -> dashSeconds(start).let { if (it > 0) VideoInfo(it) else null }
            else -> {
                var at = 0L
                var buf = first.body
                val sec = mp4Seconds { pos, n ->
                    if (pos < at || pos + n > at + buf.size) {
                        val r = get(url, pos, CHUNK, true, referer, cookie)
                        if (r != null) { at = pos; buf = r.body }
                    }
                    if (pos >= at && pos + n <= at + buf.size) buf.copyOfRange((pos - at).toInt(), (pos - at).toInt() + n) else null
                }
                if (sec > 0 || first.total > 0) VideoInfo(sec, first.total) else null
            }
        }
    } catch (_: Exception) { null }

    private fun text(url: String, referer: String, cookie: String?): Reply? = get(url, 0, 3_000_000, false, referer, cookie)

    private fun hls(url: String, referer: String, cookie: String?): VideoInfo? {
        val r = text(url, referer, cookie) ?: return null
        val t = String(r.body, Charsets.UTF_8)
        val m = hlsVariants(t, r.url)
        if (m.parts.isEmpty()) return hlsSeconds(t).let { if (it > 0) VideoInfo(it) else null }
        val best = m.best ?: return VideoInfo(0, 0, m.parts)
        val v = text(best, referer, cookie) ?: return VideoInfo(0, 0, m.parts)
        val sec = hlsSeconds(String(v.body, Charsets.UTF_8))
        return VideoInfo(sec, m.bandwidth / 8 * sec, m.parts)
    }

    /** Dailymotion: many videos at once, in seconds. */
    fun dailymotion(ids: List<String>): Map<String, Int> = try {
        val u = "https://api.dailymotion.com/videos?limit=100&fields=id,duration&ids=" + ids.joinToString(",")
        val l = JSONObject(String(get(u, 0, 500_000, false)!!.body, Charsets.UTF_8)).getJSONArray("list")
        (0 until l.length()).associate { l.getJSONObject(it).getString("id") to l.getJSONObject(it).optInt("duration") }
    } catch (_: Exception) { emptyMap() }

    fun vimeo(url: String): Int = try {
        val u = "https://vimeo.com/api/oembed.json?url=" + URLEncoder.encode(url, "UTF-8")
        JSONObject(String(get(u, 0, 100_000, false)!!.body, Charsets.UTF_8)).optInt("duration")
    } catch (_: Exception) { 0 }

    /** A YouTube video: its page is read only up to where it says the length. */
    fun youtube(url: String): Int {
        val re = Regex("\"(lengthSeconds|approxDurationMs)\":\"(\\d+)\"")
        var sec = 0
        var seen = 0
        // SOCS: skips the "before you continue" page shown in Europe
        get(url, 0, 2_500_000, false, cookie = "SOCS=CAI") { out ->
            if (out.size() - seen < 100_000) return@get false
            seen = out.size()
            sec = ytSeconds(re, out)
            sec > 0
        }?.let { r -> if (sec == 0) sec = ytSeconds(re, ByteArrayOutputStream().apply { write(r.body) }) }
        return sec
    }

    private fun ytSeconds(re: Regex, out: ByteArrayOutputStream): Int {
        val m = re.find(String(out.toByteArray(), Charsets.ISO_8859_1)) ?: return 0
        val v = m.groupValues[2].toLongOrNull() ?: return 0
        return (if (m.groupValues[1] == "lengthSeconds") v else (v + 500) / 1000).toInt()
    }

    /** Any site: asks yt-dlp (a few seconds each). */
    fun engine(url: String, jar: File?, processId: String): VideoInfo? = try {
        val r = YoutubeDLRequest(url)
        r.addOption("--dump-single-json")
        r.addOption("--no-playlist")
        r.addOption("--flat-playlist")
        r.addOption("--socket-timeout", "20")
        if (jar != null) r.addOption("--cookies", jar.absolutePath)
        if (proxyRule.isNotEmpty()) r.addOption("--proxy", proxyRule)
        val j = JSONObject(YoutubeDL.getInstance().execute(r, processId).out)
        val sec = j.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        VideoInfo(sec, maxOf(j.optLong("filesize", 0), j.optLong("filesize_approx", 0)))
    } catch (_: Exception) { null }

    internal class Variants(val best: String?, val bandwidth: Long, val parts: List<String>)

    internal companion object {
        private const val CHUNK = 65536

        /** "12:34" or "1:02:03" */
        fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

        /** Length of an HLS media playlist; 0 for a live one. */
        fun hlsSeconds(t: String): Int {
            if (!t.contains("#EXT-X-ENDLIST")) return 0
            return Regex("#EXTINF:\\s*([\\d.]+)").findAll(t).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }.toInt()
        }

        /** The qualities (and separate sound tracks) an HLS master playlist is made of. Empty if it isn't one. */
        fun hlsVariants(t: String, base: String): Variants {
            fun abs(u: String) = try { URL(URL(base), u.trim()).toString() } catch (_: Exception) { u.trim() }
            val lines = t.lines()
            val parts = mutableListOf<String>()
            var best: String? = null
            var bw = -1L
            for ((i, l) in lines.withIndex()) {
                if (l.startsWith("#EXT-X-MEDIA:")) Regex("URI=\"([^\"]+)\"").find(l)?.let { parts.add(abs(it.groupValues[1])) }
                if (!l.startsWith("#EXT-X-STREAM-INF:")) continue
                val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                parts.add(abs(u))
                val b = Regex("[:,]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (b > bw) { bw = b; best = abs(u) }
            }
            return Variants(best, maxOf(0L, bw), parts)
        }

        fun dashSeconds(t: String): Int {
            val m = Regex("mediaPresentationDuration=\"P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?\"").find(t) ?: return 0
            fun n(i: Int) = m.groupValues[i].toDoubleOrNull() ?: 0.0
            return (n(1) * 86400 + n(2) * 3600 + n(3) * 60 + n(4)).toInt()
        }

        /** Length of an MP4 file, from its header. read(position, count) gives bytes of the file, or null. */
        fun mp4Seconds(read: (Long, Int) -> ByteArray?): Int {
            fun u32(b: ByteArray, i: Int) = ((b[i].toLong() and 255) shl 24) or ((b[i + 1].toLong() and 255) shl 16) or
                ((b[i + 2].toLong() and 255) shl 8) or (b[i + 3].toLong() and 255)
            fun u64(b: ByteArray, i: Int) = (u32(b, i) shl 32) or u32(b, i + 4)
            var pos = 0L
            for (k in 0 until 40) {
                val h = read(pos, 16) ?: return 0
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                if (k == 0 && type != "ftyp" && type != "moov") return 0      // not an MP4
                val size = u32(h, 0).let { if (it == 1L) u64(h, 8) else it }
                when (type) {
                    "moov" -> pos += if (u32(h, 0) == 1L) 16 else 8          // look inside it
                    "mvhd" -> {
                        val b = read(pos, 40) ?: return 0
                        val v1 = b[8].toInt() == 1
                        val scale = u32(b, if (v1) 28 else 20)
                        val dur = if (v1) u64(b, 32) else u32(b, 24)
                        if (scale <= 0 || dur <= 0 || (!v1 && dur == 0xFFFFFFFFL)) return 0
                        return (dur / scale).toInt()
                    }
                    else -> { if (size < 8) return 0; pos += size }
                }
            }
            return 0
        }
    }
}
