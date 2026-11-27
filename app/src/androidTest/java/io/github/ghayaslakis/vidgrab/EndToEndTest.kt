package io.github.ghayaslakis.vidgrab

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The core flows, against a small test video in this repo (testdata/vidgrab-test.mp4: H.264 + AAC with
 * its index at the END, so we can tell that optimizing really ran). These must always pass.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest : E2EBase() {

    @Test
    fun a_downloadOptimizeAndDelete() {
        val card = addLink(TEST_VIDEO)
        assertTrue("Quality list: ${card.labels}", "Best available" in card.labels && "Audio only (MP3)" in card.labels)

        download(card)
        assertEquals("Auto-optimize should succeed.\n${report()}", "Done - saved", act { it.testState(card) })
        assertEquals("Saved files: ${card.files}", 1, card.files.size)
        val uri = card.files[0]
        assertTrue("Saved name: ${name(uri)}", name(uri)?.endsWith(".mp4") == true)
        assertTrue("Saved size: ${size(uri)}", size(uri) > 50_000)
        var b = boxes(head(uri))
        assertTrue("After auto-optimize the index (moov) should come before the data (mdat): $b",
            b.indexOf("moov") in 0 until b.indexOf("mdat"))

        // "More > Optimize ticked files" on the saved file
        act { a -> card.check.isChecked = true; a.testOptimizeTicked() }
        waitFor("Optimize ticked files", 180) { a ->
            val s = a.testState(card)
            when {
                s == "Optimized" -> OK
                s.startsWith("Could not optimize") || s.startsWith("Partly") -> "Optimize failed: $s"
                else -> null
            }
        }
        b = boxes(head(uri))
        assertTrue("Still faststart after optimizing again: $b", b.indexOf("moov") in 0 until b.indexOf("mdat"))
        assertTrue("Still a full file: ${size(uri)}", size(uri) > 50_000)

        // "More > Delete ticked videos": removes the card AND the file on the phone
        act { a -> card.check.isChecked = true; a.testDeleteTicked() }
        waitFor("Delete ticked videos", 30) { a -> if (a.testItems().isEmpty()) OK else null }
        assertTrue("The saved file should be deleted from the phone", !exists(uri))
    }

    @Test
    fun b_audioOnlyMp3() {
        val card = addLink(TEST_VIDEO)
        act { it.testSelect(card, "Audio only (MP3)") }
        download(card)
        assertEquals(report(), "Done - saved", act { it.testState(card) })
        val uri = card.files.single()
        assertTrue("Saved name: ${name(uri)}", name(uri)?.endsWith(".mp3") == true)
        val h = head(uri, 4)
        val id3 = h.size >= 3 && String(h, 0, 3, Charsets.US_ASCII) == "ID3"
        val frame = h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0
        assertTrue("Not an MP3 file (starts with ${h.joinToString(" ") { "%02x".format(it) }})", id3 || frame)

        act { a -> card.check.isChecked = true; a.testDeleteTicked() }
        waitFor("Delete ticked videos", 30) { a -> if (a.testItems().isEmpty()) OK else null }
        assertTrue("The saved file should be deleted from the phone", !exists(uri))
    }

    @Test
    fun c_removeTickedUnfinished() {
        val card = addLink(TEST_VIDEO)
        act { a -> card.check.isChecked = true; a.testRemoveUnfinished() }
        waitFor("Remove ticked unfinished", 30) { a -> if (a.testItems().isEmpty()) OK else null }
    }

    /** A playlist link (testdata/playlist.rss: two videos) is replaced by a card for each of its videos. */
    @Test
    fun d_playlistLink() {
        act { it.testAddLinks(TEST_PLAYLIST) }
        waitFor("the playlist's two videos to be added", 240) { a ->
            val urls = a.testItems().map { it.url }
            when {
                TEST_VIDEO in urls && TEST_VIDEO_CDN in urls -> OK
                a.testCheckErr().isNotEmpty() && !a.testStatus().startsWith("Checking") -> "The link check failed"
                else -> null
            }
        }
        assertTrue("The playlist link itself should not become a card", item(TEST_PLAYLIST) == null)
    }

    /**
     * The screen closes during a download (like swiping VidGrab away from Recents: the process stays,
     * as it does while the download service runs). A new screen must show the same, live download and
     * its result - not the saved copy of the list, which is removed here to prove it.
     */
    @Test
    fun e_downloadSurvivesClosingTheScreen() {
        val card = addLink(TEST_VIDEO)
        act { a ->
            for (c in a.testItems()) c.check.isChecked = c === card
            a.testDownloadTicked()
        }
        sc.close()
        ctx.getSharedPreferences("vidgrab", Context.MODE_PRIVATE).edit().remove("items").commit()
        sc = ActivityScenario.launch(MainActivity::class.java)
        act { it.autoConfirm = true }
        waitFor("the download to finish on the new screen", 300) { a ->
            val c = a.testItems().firstOrNull { it.url == TEST_VIDEO }
            val s = if (c == null) "" else a.testState(c)
            when {
                c == null -> "The new screen lost the download"
                s.startsWith("Done") -> OK
                "Tap Start to retry" in s -> "Download failed: $s"
                else -> null
            }
        }
        val c = item(TEST_VIDEO)!!
        assertTrue("The new screen should show the same download", c === card)
        assertEquals(report(), "Done - saved", act { it.testState(c) })
        assertEquals("Saved files: ${c.files}", 1, c.files.size)
    }

    /**
     * Several links pasted at once (checked in parallel), nothing ticked, "Download all", with
     * "Downloads at the same time" = 1: both finish, and never more than one runs at a time.
     */
    @Test
    fun f_downloadAllOneAtATime() {
        act { it.testSetParallel(1) }
        try {
            act { it.testAddLinks(TEST_VIDEO + "\n" + TEST_VIDEO_CDN) }
            waitFor("both links to get a card", 240) { a ->
                val urls = a.testItems().map { it.url }
                when {
                    TEST_VIDEO in urls && TEST_VIDEO_CDN in urls -> OK
                    a.testCheckErr().isNotEmpty() && !a.testStatus().startsWith("Checking") -> "The link check failed"
                    else -> null
                }
            }
            act { a ->
                for (c in a.testItems()) c.check.isChecked = false
                a.testDownloadTicked()                       // nothing ticked: downloads all
            }
            var most = 0
            waitFor("both downloads to finish", 600) { a ->
                most = maxOf(most, a.testRunningNow())
                val states = a.testItems().map { a.testState(it) }
                when {
                    states.any { "Tap Start to retry" in it } -> "A download failed: $states"
                    states.size == 2 && states.all { it.startsWith("Done") } -> OK
                    else -> null
                }
            }
            assertTrue("At most 1 download should run at a time, saw $most", most <= 1)
            for (c in act { it.testItems() }) assertEquals(report(), "Done - saved", act { it.testState(c) })
        } finally {
            act { it.testSetParallel(2) }
        }
    }

    /**
     * The card's "Download only a part" (1 to 3 s of the 5 s video), the download history, and the same
     * link added again saying it was downloaded before.
     */
    @Test
    fun h_partOfAVideoAndHistory() {
        val card = addLink(TEST_VIDEO)
        act { a -> a.testSetCut(card, 1, 3) }
        download(card)
        assertTrue(report(), act { it.testState(card) }.startsWith("Done - saved"))
        val uri = card.files.single()
        val ms = durationMs(uri)
        assertTrue("Only part of the 5 s video should be saved, got $ms ms", ms in 1000..4200)
        val h = act { it.testHistory() }
        assertTrue("The download should be in the history: ${h.map { it.url }}",
            h.any { it.url == TEST_VIDEO && uri in it.files })

        // deleting the video keeps its history entry, so the link added again is recognised
        act { a -> card.check.isChecked = true; a.testDeleteTicked() }
        waitFor("Delete ticked videos", 30) { a -> if (a.testItems().isEmpty()) OK else null }
        val again = addLink(TEST_VIDEO)
        val s2 = act { it.testState(again) }
        assertTrue("A link downloaded before should say so: $s2", s2.startsWith("Downloaded before"))
    }

    /** "Quality for new links" = Audio only: a new card starts on Audio only (MP3). */
    @Test
    fun g_defaultQuality() {
        act { it.testSetDefaultQuality(-1) }
        try {
            val card = addLink(TEST_VIDEO)
            assertEquals("Audio only (MP3)", act { it.testQuality(card) })
        } finally {
            act { it.testSetDefaultQuality(0) }
        }
    }
}
