package com.example.vidgrab

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before

/**
 * Starts the real app on a clean list and drives it through MainActivity's test hooks
 * (see "UI TEST CONTRACT" in MainActivity). Everything runs for real: the bundled yt-dlp,
 * Python and ffmpeg, the network, MediaStore.
 */
abstract class E2EBase {
    protected val ctx: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var sc: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        // Granted up front so the permission dialog never covers the app.
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        // Empty list, default folder, mobile data allowed (the emulator's network may not count as Wi-Fi).
        ctx.getSharedPreferences("vidgrab", Context.MODE_PRIVATE).edit()
            .remove("items").remove("tree")
            .putBoolean("wifiOnly", false).putBoolean("optimize", true)
            .commit()
        sc = ActivityScenario.launch(MainActivity::class.java)
        act { it.autoConfirm = true }
        // The first start unpacks Python + ffmpeg and updates the engine: this can take a while.
        waitFor("the download engine to start", 300) { a ->
            if (a.testStatus().startsWith("Could not start")) "Engine failed to start" else if (a.testReady()) OK else null
        }
        log("engine ready: " + act { it.testStatus() })
    }

    @After
    fun close() {
        try { act { it.testRemoveAll() } } catch (_: Throwable) {}
        sc.close()
    }

    /** Runs f on the main thread with the activity and returns its result. */
    protected fun <T> act(f: (MainActivity) -> T): T {
        val box = arrayOfNulls<Any>(1)
        sc.onActivity { box[0] = f(it) }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    /**
     * Polls check every half second: it returns null to keep waiting, OK when done, or a failure reason.
     * Fails with everything the app knows (status, card states, full errors) so CI shows why.
     */
    protected fun waitFor(what: String, secs: Int, check: (MainActivity) -> String?) {
        val end = System.currentTimeMillis() + secs * 1000L
        while (true) {
            val r = act(check)
            if (r == OK) return
            if (r != null) fail("$r (while waiting for $what)\n\n${report()}")
            if (System.currentTimeMillis() > end) fail("Timed out after ${secs}s waiting for $what\n\n${report()}")
            Thread.sleep(500)
        }
    }

    /** What the app shows right now, plus the full error texts. */
    protected fun report(): String = act { a ->
        val sb = StringBuilder("App status: ").append(a.testStatus())
        if (a.testCheckErr().isNotEmpty()) sb.append("\nLink-check errors:\n").append(a.testCheckErr().take(2000))
        for (c in a.testItems()) {
            sb.append("\nCard '").append(c.title).append("': state='").append(a.testState(c))
                .append("' done=").append(c.done).append(" files=").append(c.files)
            if (c.err.isNotEmpty()) sb.append("\n  error: ").append(c.err.take(2000))
        }
        sb.toString()
    }

    protected fun item(url: String): MainActivity.Item? = act { a -> a.testItems().firstOrNull { it.url == url } }

    /** Pastes a link and waits until the automatic link check has added its card. */
    protected fun addLink(url: String, secs: Int = 180): MainActivity.Item {
        act { it.testAddLinks(url) }
        waitFor("the link check to add a card for $url", secs) { a ->
            when {
                a.testItems().any { it.url == url } -> OK
                a.testCheckErr().isNotEmpty() && !a.testStatus().startsWith("Checking") -> "The link check failed"
                else -> null
            }
        }
        val card = item(url)!!
        log("card added: '${card.title}' qualities=${card.labels}")
        return card
    }

    /** Ticks only this card, taps Download and waits until it is done (or fails fast on an error). */
    protected fun download(target: MainActivity.Item, secs: Int = 600) {
        act { a ->
            for (c in a.testItems()) c.check.isChecked = c === target
            a.testDownloadTicked()
        }
        waitFor("'${target.title}' to download", secs) { a ->
            val s = a.testState(target)
            when {
                s.startsWith("Done") -> OK
                "Tap Start to retry" in s -> "Download failed: $s"
                s.startsWith("Already running") || s.startsWith("The download engine is still starting") -> "Did not start: $s"
                else -> null
            }
        }
        log("downloaded '${target.title}': ${act { it.testState(target) }} -> ${target.files}")
    }

    /** The first bytes of a saved file (read back through MediaStore, like any other app would). */
    protected fun head(uri: String, n: Int = 1 shl 20): ByteArray =
        ctx.contentResolver.openInputStream(Uri.parse(uri))!!.use { i ->
            val buf = ByteArray(n)
            var got = 0
            while (got < n) { val k = i.read(buf, got, n - got); if (k < 0) break; got += k }
            buf.copyOf(got)
        }

    protected fun size(uri: String): Long =
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { it.statSize }

    protected fun name(uri: String): String? =
        ctx.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** True while MediaStore still has the file. */
    protected fun exists(uri: String): Boolean = try {
        ctx.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { it.count > 0 } ?: false
    } catch (_: Exception) { false }

    /** Top-level MP4 box names in file order ("ftyp", "moov", "mdat", ...). */
    protected fun boxes(b: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var p = 0L
        while (p + 8 <= b.size) {
            val i = p.toInt()
            var len = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
            out.add(String(b, i + 4, 4, Charsets.US_ASCII))
            if (len == 1L && p + 16 <= b.size) {          // 64-bit size
                len = 0
                for (k in 8 until 16) len = (len shl 8) or (b[i + k].toLong() and 0xFF)
            }
            if (len < 8) break
            p += len
        }
        return out
    }

    protected fun log(msg: String) { Log.i("VidGrabE2E", msg) }

    companion object {
        const val OK = "ok"
        const val TEST_VIDEO = "https://raw.githubusercontent.com/ghayas-lakis/vidgrab/main/testdata/vidgrab-test.mp4"
    }
}
