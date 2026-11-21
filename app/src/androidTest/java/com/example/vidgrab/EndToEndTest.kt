package com.example.vidgrab

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
}
