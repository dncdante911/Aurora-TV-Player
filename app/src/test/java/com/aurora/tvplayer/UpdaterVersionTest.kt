package com.aurora.tvplayer

import com.aurora.tvplayer.data.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test
    fun newerVersionsAreDetected() {
        assertTrue(Updater.isNewer("3.1.4", "3.1.3"))
        assertTrue(Updater.isNewer("3.2.0", "3.1.10"))
        assertTrue(Updater.isNewer("4", "3.9.9"))
        assertTrue(Updater.isNewer("v3.1.4", "3.1.3")) // tags with a leading v
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(Updater.isNewer("3.1.3", "3.1.3"))
        assertFalse(Updater.isNewer("3.1.3", "3.1.3.0"))
        assertFalse(Updater.isNewer("3.1.2", "3.1.3"))
        assertFalse(Updater.isNewer("3.1.10", "3.2"))
        assertFalse(Updater.isNewer("latest", "3.1.3"))
    }
}
