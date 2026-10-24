package com.aurora.tvplayer

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aurora.tvplayer.data.LiveLook
import com.aurora.tvplayer.data.Occasion
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.decor.DecorLayer
import com.aurora.tvplayer.ui.decor.LocalOccasion
import com.aurora.tvplayer.ui.decor.rememberDecor
import com.aurora.tvplayer.ui.home.HomeScreen
import com.aurora.tvplayer.ui.theme.AuroraBackground
import com.aurora.tvplayer.ui.theme.AuroraTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The home screen in every season and holiday at 1920×1080, to check the decoration.
 * Run with AURORA_SCREENSHOTS=1; images go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DecorScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as AuroraApp
    private val folder = "/storage/CA44AF7444AF6245/Elements/Season_10"

    private fun shoot(occasion: Occasion) {
        assumeTrue("Set AURORA_SCREENSHOTS=1 to render screenshots", System.getenv("AURORA_SCREENSHOTS") != null)
        app.settings.setLiveLook(LiveLook.ON)
        listOf("06. Спросите Дживса", "05. Фан-фикшн", "04. Бумажная луна", "03. Исцелённая душа").forEachIndexed { i, title ->
            app.playback.save("file://$folder/$title.mkv", title, folder, (17 + i * 9) * 60_000L, 42 * 60_000L)
        }
        val vm = MainViewModel(app)
        // The decoration never stops moving, so the test drives the clock itself.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AuroraTheme {
                CompositionLocalProvider(LocalOccasion provides occasion) {
                    val decor = rememberDecor(occasion, home = true)
                    AuroraBackground(animated = true, decor = decor) {
                        HomeScreen(vm)
                        DecorLayer(decor, home = true)
                    }
                }
            }
        }
        repeat(56) { compose.mainClock.advanceTimeBy(50) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "decor-${occasion.ordinal}-${occasion.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun winter() = shoot(Occasion.WINTER)
    @Test fun spring() = shoot(Occasion.SPRING)
    @Test fun summer() = shoot(Occasion.SUMMER)
    @Test fun autumn() = shoot(Occasion.AUTUMN)
    @Test fun newYear() = shoot(Occasion.NEW_YEAR)
    @Test fun easter() = shoot(Occasion.EASTER)
    @Test fun valentine() = shoot(Occasion.VALENTINE)
    @Test fun independence() = shoot(Occasion.INDEPENDENCE)
    @Test fun halloween() = shoot(Occasion.HALLOWEEN)
}
