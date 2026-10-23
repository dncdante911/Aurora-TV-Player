package com.aurora.tvplayer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.aurora.tvplayer.player.Panel
import com.aurora.tvplayer.player.PanelOption
import com.aurora.tvplayer.player.PlayerActions
import com.aurora.tvplayer.player.PlayerOverlay
import com.aurora.tvplayer.player.PlayerUiState
import com.aurora.tvplayer.ui.MainViewModel
import com.aurora.tvplayer.ui.browser.BrowserEntry
import com.aurora.tvplayer.ui.browser.BrowserLayout
import com.aurora.tvplayer.ui.browser.EntryKind
import com.aurora.tvplayer.ui.browser.NetworkScreen
import com.aurora.tvplayer.ui.components.AuroraChip
import com.aurora.tvplayer.ui.components.FocusMemory
import com.aurora.tvplayer.ui.device.DeviceScreen
import com.aurora.tvplayer.ui.home.HomeScreen
import com.aurora.tvplayer.ui.theme.AuroraBackground
import com.aurora.tvplayer.ui.theme.AuroraTheme
import com.aurora.tvplayer.ui.theme.Icons
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real screenshots of the app for the forum and README, rendered from the Compose code at
 * 1920×1080 (960×540 dp, xhdpi, TV). Sample data only; nothing is read from a device.
 * Run with AURORA_SCREENSHOTS=1; images go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as AuroraApp
    private val folder = "/storage/CA44AF7444AF6245/Elements/Season_10"
    private val episodes = listOf(
        "01. Чёрное", "02. Рейхенбах", "03. Исцелённая душа", "04. Бумажная луна",
        "05. Фан-фикшн", "06. Спросите Дживса", "07. Маятник", "08. Ночной экспресс",
    )
    private fun uri(title: String) = "file://$folder/$title.mkv"

    @Before
    fun setUp() {
        assumeTrue("Set AURORA_SCREENSHOTS=1 to render screenshots", System.getenv("AURORA_SCREENSHOTS") != null)
        app.settings.setAnimatedBackground(false)
        val store = app.playback
        store.save(uri(episodes[1]), episodes[1], folder, 42 * 60_000L, 42 * 60_000L)
        store.save(uri(episodes[2]), episodes[2], folder, 44 * 60_000L, 44 * 60_000L)
        store.save(uri(episodes[3]), episodes[3], folder, 43 * 60_000L, 43 * 60_000L)
        store.save(uri(episodes[4]), episodes[4], folder, 42 * 60_000L, 42 * 60_000L)
        store.save(uri(episodes[5]), episodes[5], folder, 17 * 60_000L, 42 * 60_000L)
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AuroraTheme { content() } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val vm by lazy { MainViewModel(app) }

    @Test
    fun home() = shoot("01-home") {
        AuroraBackground(animated = false) { HomeScreen(vm) }
    }

    @Test
    fun files() {
        val entries = listOf(
            BrowserEntry("$folder/Extras", "Extras", EntryKind.FOLDER, 0, null),
        ) + episodes.mapIndexed { i, title ->
            BrowserEntry("$folder/$title.mkv", "$title.mkv", EntryKind.VIDEO, (2_150L + i * 37) shl 20, uri(title))
        }
        shoot("02-files") {
            AuroraBackground(animated = false) {
                val memory = remember { FocusMemory() }
                val first = remember { FocusRequester() }
                BrowserLayout(
                    section = "Файлы",
                    title = "Season_10",
                    subtitle = "USB-накопитель  /  Elements  /  Season_10",
                    memory = memory,
                    firstTool = first,
                    reloadKey = folder,
                    tools = {
                        AuroraChip("Диски", onClick = {}, icon = Icons.Storage)
                        AuroraChip("Назад", onClick = {}, icon = Icons.Up)
                        AuroraChip("Все файлы", onClick = {}, icon = Icons.Eye)
                        AuroraChip("Поиск", onClick = {}, icon = Icons.Search)
                    },
                    loading = false,
                    message = null,
                    entries = entries,
                    playback = app.playback,
                    onOpen = {},
                )
            }
        }
    }

    @Test
    fun network() = shoot("03-network") {
        AuroraBackground(animated = false) { NetworkScreen(vm) }
    }

    @Test
    fun settings() = shoot("04-settings") {
        AuroraBackground(animated = false) { DeviceScreen(vm) }
    }

    @Test
    fun supportReal() = shoot("08-support") {
        AuroraBackground(animated = false) { com.aurora.tvplayer.ui.support.SupportScreen() }
    }

    @Test
    fun updateBanner() = shoot("09-update-banner") {
        AuroraBackground(animated = false) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(48.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                val release = com.aurora.tvplayer.data.Release("3.2.0", "Новое: подписи серий и быстрый поиск", "", 0, null)
                com.aurora.tvplayer.ui.home.UpdateBanner(com.aurora.tvplayer.data.UpdateState.Downloading(release, 0.45f), {}, {})
                com.aurora.tvplayer.ui.home.UpdateBanner(com.aurora.tvplayer.data.UpdateState.Ready(release, java.io.File("x")), {}, {})
            }
        }
    }

    /** Placeholders only: real details live in data/Support.kt. */
    @Test
    fun support() = shoot("08-support-example") {
        AuroraBackground(animated = false) {
            com.aurora.tvplayer.ui.support.SupportScreen(listOf(
                com.aurora.tvplayer.data.Support.Method("Monobank · банка", "Любая карта Visa / Mastercard, Apple Pay, Google Pay",
                    "https://send.monobank.ua/jar/EXAMPLE", "send.monobank.ua/jar/EXAMPLE"),
                com.aurora.tvplayer.data.Support.Method("USDT · TRC-20", "Криптовалюта, из любой страны. Только сеть TRON",
                    "TEXAMPLEaddressEXAMPLEaddressEXAMP", "TEXAMPLEaddressEXAMPLEaddressEXAMP"),
                com.aurora.tvplayer.data.Support.Method("TON", "Telegram Wallet, Tonkeeper",
                    "ton://transfer/UQEXAMPLE", "UQEXAMPLE"),
            ))
        }
    }

    private fun playerState() = PlayerUiState().apply {
        title = "05. Фан-фикшн"
        subtitle = "5 из 23 · Season_10"
        duration = 42 * 60_000L + 3_000
        position = 14 * 60_000L + 20_000
        buffered = 16 * 60_000L
        playing = true
        hasPrevious = true
        hasNext = true
    }

    /** Stand-in for the video frame: the overlay is real, the picture behind it is a gradient. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF1B2A3A), Color(0xFF0B1118), Color(0xFF2A1E16), Color(0xFF0E0F14))),
            ),
        ) { content() }
    }

    @Test
    fun player() = shoot("05-player") {
        Frame { PlayerOverlay(playerState(), NoActions) }
    }

    @Test
    fun playerAudio() = shoot("06-player-audio") {
        val state = playerState().apply {
            panel = Panel.Options("Аудиодорожка", listOf(
                PanelOption("Автоматически", "Вывод по настройкам ТВ") {},
                PanelOption("Русский · Дубляж", "AC3 · 5.1 · 448 кбит/с", selected = true) {},
                PanelOption("Русский · Многоголосый", "AAC · стерео · 192 кбит/с") {},
                PanelOption("Английский · Original", "EAC3 · 5.1 · 640 кбит/с") {},
                PanelOption("Английский · Комментарии", "AAC · стерео") {},
            ))
        }
        Frame { PlayerOverlay(state, NoActions) }
    }

    @Test
    fun playerSettings() = shoot("07-player-settings") {
        val state = playerState().apply {
            panel = Panel.Options("Настройки просмотра", listOf(
                PanelOption("Качество видео / HDR", "Выбор дорожки, если поток их предлагает", icon = Icons.Hdr) {},
                PanelOption("Формат изображения", "По размеру — без обрезки", icon = Icons.AspectRatio) {},
                PanelOption("Скорость", "1×", icon = Icons.Speed) {},
                PanelOption("Повтор файла", "Выключен", icon = Icons.Repeat) {},
                PanelOption("Подсветка Ambilight", "Управляется телевизором", icon = Icons.Lightbulb) {},
                PanelOption("Информация о декодерах", "Поток, декодер, возможности ТВ", icon = Icons.Info) {},
            ))
        }
        Frame { PlayerOverlay(state, NoActions) }
    }

    private object NoActions : PlayerActions {
        override fun togglePlay() = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun previous() = Unit
        override fun next() = Unit
        override fun exit() = Unit
        override fun audio() = Unit
        override fun subtitles() = Unit
        override fun settings() = Unit
        override fun retry() = Unit
        override fun softwareDecoder() = Unit
        override fun errorDetails() = Unit
        override fun restart() = Unit
        override fun closePanel() = Unit
        override fun userActivity() = Unit
    }
}
