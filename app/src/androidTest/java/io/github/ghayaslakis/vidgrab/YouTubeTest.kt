package io.github.ghayaslakis.vidgrab

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real YouTube download (the first YouTube video, 19 s). CI runs this separately and only WARNS when
 * it fails: YouTube often blocks data-center IPs like CI's, so a failure here is not always a bug.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeTest : E2EBase() {

    @Test
    fun youtubeDownload() {
        val card = addLink("https://www.youtube.com/watch?v=jNQXAC9IVRw", 240)
        // the smallest resolution keeps the test quick
        val low = card.choices.filter { it > 0 }.minOrNull()
        if (low != null) act { it.testSelect(card, card.labels[card.choices.indexOf(low)]) }
        download(card, 600)
        assertTrue(report(), act { it.testState(card) }.startsWith("Done"))
        val uri = card.files.single()
        assertTrue("Saved size: ${size(uri)}", size(uri) > 100_000)
        act { a -> card.check.isChecked = true; a.testDeleteTicked() }
        waitFor("Delete ticked videos", 30) { a -> if (a.testItems().isEmpty()) OK else null }
    }
}
