package com.nuvio.tv.core.recommendations

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramBuilderTest {

    @Test
    fun `watch next content id match does not delete prefix collisions`() {
        assertTrue(watchNextIdMatchesContentId("wn_tt1", "tt1"))
        assertTrue(watchNextIdMatchesContentId("wn_tt1_s2e3", "tt1"))

        assertFalse(watchNextIdMatchesContentId("wn_tt10", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt10_s2e3", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1extra", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1_series", "tt1"))
        assertFalse(watchNextIdMatchesContentId(null, "tt1"))
    }

    @Test
    fun `program progress falls back to percent when position or duration is missing`() {
        assertEquals(6000 to 3000, progress(position = 3000, duration = 6000).programProgressMillis())
        // Trakt: real duration, no position.
        assertEquals(6000 to 3000, progress(duration = 6000, percent = 50f).programProgressMillis())
        // Simkl: percent only.
        assertEquals(100_000 to 25_000, progress(percent = 25f).programProgressMillis())

        assertNull(progress().programProgressMillis())
        assertNull(progress(percent = 0f).programProgressMillis())
    }

    private fun progress(position: Long = 0, duration: Long = 0, percent: Float? = null) = WatchProgress(
        contentId = "tt1",
        contentType = "movie",
        name = "Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1",
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = 0,
        progressPercent = percent
    )
}