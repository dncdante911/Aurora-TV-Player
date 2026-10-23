package com.aurora.tvplayer

import com.aurora.tvplayer.player.RefreshRate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRateTest {
    @Test
    fun filmRatesNeedMatchingScreens() {
        assertTrue(RefreshRate.exact(23.976f, 23.976f))
        assertTrue(RefreshRate.exact(23.98f, 23.976f)) // rounded value reported by some TVs
        assertTrue(RefreshRate.exact(47.952f, 23.976f))
        assertFalse(RefreshRate.fits(60f, 23.976f)) // 3:2 pulldown judder
        assertFalse(RefreshRate.fits(50f, 23.976f))
    }

    @Test
    fun nearRatesAreSmoothButNotExact() {
        assertTrue(RefreshRate.fits(24f, 23.976f))
        assertFalse(RefreshRate.exact(24f, 23.976f))
        assertTrue(RefreshRate.fits(60f, 59.94f))
        assertFalse(RefreshRate.exact(60f, 59.94f))
    }

    @Test
    fun tvRatesAndMultiples() {
        assertTrue(RefreshRate.exact(50f, 25f))
        assertTrue(RefreshRate.exact(50f, 50f))
        assertTrue(RefreshRate.exact(60f, 30f))
        assertTrue(RefreshRate.exact(120f, 60f))
        assertFalse(RefreshRate.fits(60f, 50f))
        assertFalse(RefreshRate.fits(50f, 60f))
    }

    @Test
    fun unknownFrameRateNeverForcesASwitch() {
        assertTrue(RefreshRate.exact(60f, 0f))
        assertTrue(RefreshRate.fits(60f, -1f))
    }
}
