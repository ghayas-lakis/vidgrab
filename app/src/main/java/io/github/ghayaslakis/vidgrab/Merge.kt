package io.github.ghayaslakis.vidgrab

import java.io.File
import java.util.Locale

/**
 * Joins downloaded videos into one, in the given order, with the bundled ffmpeg.
 *
 * When every part has the same format (codecs, picture size, audio rate and channels; the usual case
 * for the episodes of one series) they are joined without re-encoding, which takes seconds. Otherwise
 * each part is fitted into the first one's picture size (with black bars where the shape differs) and
 * the whole is re-encoded, which takes about as long as the videos play, or longer on a slow phone.
 */
internal object Merge {
    class Result(val file: File, val seconds: Double, val reencoded: Boolean)

    private class Info(val dur: Double, val vcodec: String?, val w: Int, val h: Int,
                       val acodec: String?, val rate: Int, val layout: String?) {
        fun sameFormat(o: Info) = vcodec == o.vcodec && w == o.w && h == o.h &&
            acodec == o.acodec && rate == o.rate && layout == o.layout
    }

    private val PROGRESS_LINE = Regex("^[a-z_]+=\\S*$")

    /** Runs ffmpeg; returns its exit code and its messages (progress lines go to onProgress only). */
    private fun exec(bin: File, env: (ProcessBuilder) -> Unit, args: List<String>,
                     onProgress: ((String) -> Unit)? = null): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        env(pb)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val log = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { l ->
            if (PROGRESS_LINE.matches(l)) onProgress?.invoke(l)
            else if (log.length < 40_000) log.append(l).append('\n')
        }
        return p.waitFor() to log.toString().trim()
    }

    /** Reads a file's duration and its first video and audio stream from ffmpeg's description of it. */
    private fun probe(bin: File, env: (ProcessBuilder) -> Unit, f: File): Info {
        val out = exec(bin, env, listOf("-hide_banner", "-nostdin", "-i", f.absolutePath)).second
        val dur = Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(out)?.destructured
            ?.let { (h, m, s) -> h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble() } ?: 0.0
        val v = Regex("Stream #\\d+:\\d+[^\\n]*?: Video: (\\w+)[^\\n]*?\\b(\\d{2,5})x(\\d{2,5})\\b").find(out)
        val a = Regex("Stream #\\d+:\\d+[^\\n]*?: Audio: (\\w+)[^\\n]*?(\\d+) Hz, ([^,\\n]+)").find(out)
        return Info(dur, v?.groupValues?.get(1), v?.groupValues?.get(2)?.toInt() ?: 0,
            v?.groupValues?.get(3)?.toInt() ?: 0, a?.groupValues?.get(1), a?.groupValues?.get(2)?.toInt() ?: 0,
            a?.groupValues?.get(3)?.trim())
    }

    private fun even(x: Int, fallback: Int) = if (x <= 0) fallback else x - x % 2

    /**
     * Writes "<name>.<ext>" into dir and returns it. progress gets 0-99 while re-encoding.
     * Throws with ffmpeg's messages when the parts cannot be joined.
     */
    fun run(bin: File, env: (ProcessBuilder) -> Unit, parts: List<File>, dir: File, name: String,
            progress: (Int) -> Unit): Result {
        if (!bin.exists()) throw Exception("The video tools (ffmpeg) are missing from this build")
        if (parts.size < 2) throw Exception("Nothing to merge")
        parts.firstOrNull { !it.exists() }?.let { throw Exception("A downloaded video is missing: ${it.name}") }
        dir.mkdirs()
        // the output can be as big as all the parts together
        if (dir.usableSpace < parts.sumOf { it.length() } + 50_000_000L) throw Exception("Not enough free space to merge the videos")
        val info = parts.map { probe(bin, env, it) }
        val total = info.sumOf { it.dur }
        val ext0 = parts[0].extension.lowercase().ifEmpty { "mp4" }

        // 1. Same format everywhere: join without re-encoding.
        if (info.all { it.sameFormat(info[0]) }) {
            val out = File(dir, "$name.$ext0")
            val list = File(dir, "parts.txt")
            list.writeText(parts.joinToString("\n") { "file '" + it.absolutePath.replace("'", "'\\''") + "'" } + "\n")
            val args = mutableListOf("-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "concat", "-safe", "0", "-i", list.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-c", "copy")
            if (ext0 in listOf("mp4", "m4v", "mov", "m4a")) args += listOf("-movflags", "+faststart")
            args += out.absolutePath
            val (code, log) = exec(bin, env, args)
            list.delete()
            if (code == 0 && out.length() > 0) return Result(out, total, false)
            out.delete()
            // audio only (e.g. MP3s): there is nothing to re-encode them into here
            if (info[0].vcodec == null) throw Exception("ffmpeg could not join the files (exit code $code):\n" + log.takeLast(1500))
        }
        if (info.any { it.vcodec == null })
            throw Exception("These files can't be merged: some are audio only and some are videos")

        // 2. Different formats: fit each part into the first one's size and re-encode everything.
        val w = even(info[0].w, 720)
        val h = even(info[0].h, 1280)
        val out = File(dir, "$name.mp4")
        val inputs = mutableListOf<String>()
        parts.forEach { inputs += listOf("-i", it.absolutePath) }
        val anyAudio = info.any { it.acodec != null }
        // parts without sound get silence of their length, so sound and picture stay together
        val silent = mutableMapOf<Int, Int>()
        var next = parts.size
        if (anyAudio) info.forEachIndexed { i, x ->
            if (x.acodec == null) {
                inputs += listOf("-f", "lavfi", "-t", String.format(Locale.US, "%.3f", maxOf(x.dur, 0.1)),
                    "-i", "anullsrc=r=44100:cl=stereo")
                silent[i] = next++
            }
        }
        val graph = StringBuilder()
        val cat = StringBuilder()
        for (i in parts.indices) {
            graph.append("[$i:v]scale=$w:$h:force_original_aspect_ratio=decrease,")
                .append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v$i];")
            cat.append("[v$i]")
            if (anyAudio) {
                graph.append("[${silent[i] ?: i}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a$i];")
                cat.append("[a$i]")
            }
        }
        graph.append(cat).append("concat=n=${parts.size}:v=1:a=${if (anyAudio) 1 else 0}[v]")
        if (anyAudio) graph.append("[a]")
        val common = mutableListOf("-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-progress", "pipe:1", "-nostats")
        common += inputs
        common += listOf("-filter_complex", graph.toString(), "-map", "[v]")
        if (anyAudio) common += listOf("-map", "[a]", "-c:a", "aac", "-b:a", "128k")
        // H.264 when this ffmpeg has it, otherwise MPEG-4 (plays everywhere too, just bigger)
        val encoders = listOf(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23"),
            listOf("-c:v", "mpeg4", "-q:v", "3"))
        var lastLog = ""
        for (enc in encoders) {
            val args = common + enc + listOf("-movflags", "+faststart", out.absolutePath)
            val (code, log) = exec(bin, env, args) { l ->
                // out_time_us (and out_time_ms, despite its name) are in microseconds
                if (total > 0 && (l.startsWith("out_time_us=") || l.startsWith("out_time_ms="))) {
                    val us = l.substringAfter('=').toLongOrNull() ?: return@exec
                    progress((us / 1e6 / total * 100).toInt().coerceIn(0, 99))
                }
            }
            if (code == 0 && out.length() > 0) return Result(out, total, true)
            out.delete()
            lastLog = log
            val missing = log.contains("Unknown encoder", true) || log.contains("Encoder not found", true) ||
                log.contains("encoder", true) && log.contains(enc[1])
            if (!missing) break
        }
        throw Exception("ffmpeg could not merge the videos:\n" + lastLog.takeLast(1500))
    }
}
