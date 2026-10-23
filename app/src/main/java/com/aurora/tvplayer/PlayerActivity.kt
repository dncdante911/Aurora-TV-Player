package com.aurora.tvplayer

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aurora.tvplayer.data.AmbientLighting
import com.aurora.tvplayer.data.DecoderPreference
import com.aurora.tvplayer.data.FrameRateMode
import com.aurora.tvplayer.player.RefreshRate
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import com.aurora.tvplayer.data.Media
import com.aurora.tvplayer.player.Panel
import com.aurora.tvplayer.player.PanelOption
import com.aurora.tvplayer.player.PlayRequest
import com.aurora.tvplayer.player.PlayerActions
import com.aurora.tvplayer.player.PlayerOverlay
import com.aurora.tvplayer.player.PlayerUiState
import com.aurora.tvplayer.player.RoutingDataSource
import com.aurora.tvplayer.ui.theme.AuroraTheme
import com.aurora.tvplayer.ui.theme.Icons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen player. The ExoPlayer exists only between onStart and onStop, so leaving the app
 * frees the hardware decoder; the position is restored on return.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerActions {
    private lateinit var request: PlayRequest
    private lateinit var playerView: PlayerView
    private val ui = PlayerUiState()
    private val store by lazy { aurora.playback }
    /** Track preferences, kept in the 2.x "library" file and keys. */
    private val trackPrefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var ticker: Job? = null
    private var hideJob: Job? = null
    private var hintJob: Job? = null
    private var startIndex = 0
    private var startPosition = C.TIME_UNSET
    private var lastError: PlaybackException? = null
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var lastSavedAt = 0L
    private var playedMs = 0L
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var externalSubtitle: MediaItem.SubtitleConfiguration? = null
    private var decoders: DecoderManager? = null
    /** Set by "Программный декодер" on the error card; survives onStop/onStart. */
    private var forceSoftwareVideo = false
    private var forceSoftwareAudio = false
    private var matchedFps = 0f
    private var judderHintShown = false

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::attachSubtitle) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PlayRequest.from(intent) ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startIndex = request.index
        resizeMode = trackPrefs.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = this@PlayerActivity.resizeMode
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContent {
            AuroraTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView({ playerView }, Modifier.fillMaxSize())
                    PlayerOverlay(ui, this@PlayerActivity)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::request.isInitialized) initializePlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    private fun initializePlayer() {
        if (player != null) return
        val settings = aurora.settings.current
        ui.forwardStepMs = settings.forwardStepSec * 1000L
        ui.backStepMs = settings.backStepSec * 1000L
        // Hardware decoders first (the only path to 4K60 and Dolby Vision); FFmpeg fills the gaps
        // for codecs the TV lacks (DTS, TrueHD, MPEG-2, VC-1 ...) or when asked explicitly.
        val manager = DecoderManager(
            if (forceSoftwareVideo) DecoderMode.FFMPEG else videoMode(settings.decoder),
            if (forceSoftwareAudio) DecoderMode.FFMPEG else audioMode(settings.decoder),
        )
        decoders = manager
        val renderers = NextRenderersFactory(this).setDecoderManager(manager)
        renderers.setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(this)))
            .setVideoChangeFrameRateStrategy(
                if (settings.frameRate == FrameRateMode.SEAMLESS) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
            )
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.pauseAtEndOfMediaItems = !settings.autoplayNext
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
        })
        exo.trackSelectionParameters = savedTrackParameters(exo)
        exo.addListener(listener)
        val items = request.items.mapIndexed { i, item ->
            MediaItem.Builder().setUri(item.uri).setMediaId(item.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                .apply { if (i == startIndex) externalSubtitle?.let { setSubtitleConfigurations(listOf(it)) } }
                .build()
        }
        val saved = store.progress(request.items[startIndex].uri)
        val position = if (startPosition != C.TIME_UNSET) startPosition else if (saved.started) saved.positionMs else 0L
        exo.setMediaItems(items, startIndex, position)
        manager.attach(exo)
        exo.prepare()
        exo.play()
        player = exo
        session = runCatching { MediaSession.Builder(this, exo).build() }.getOrNull()
        playerView.player = exo
        updateItemInfo()
        if (startPosition == C.TIME_UNSET && position > 5_000) hint("Продолжаем с ${Media.formatTime(position)}")
        showControls()
        ticker = lifecycleScope.launch {
            while (isActive) {
                tick()
                delay(500)
            }
        }
    }

    private fun releasePlayer() {
        val exo = player ?: return
        savePosition()
        startIndex = exo.currentMediaItemIndex.coerceAtLeast(0)
        startPosition = exo.currentPosition
        ticker?.cancel()
        decoders?.detach()
        decoders = null
        preferDisplayMode(0)
        matchedFps = 0f
        session?.release()
        session = null
        playerView.player = null
        exo.removeListener(listener)
        exo.release()
        player = null
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                request.items.getOrNull((player?.currentMediaItemIndex ?: 0) - 1)?.let { store.markWatched(it.uri) }
            }
            // A saved audio choice is re-matched by language/label in the next file.
            if (trackPrefs.getBoolean("audio_custom", false)) player?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            }
            ui.ended = false
            updateItemInfo()
            showControls()
        }

        override fun onTracksChanged(tracks: Tracks) {
            applySavedAudioTrack(tracks)
            player?.videoFormat?.frameRate?.let(::matchFrameRate)
        }

        override fun onPlaybackStateChanged(state: Int) {
            ui.buffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> {
                    lastError = null
                    ui.error = null
                }
                Player.STATE_ENDED -> {
                    request.items.getOrNull(player?.currentMediaItemIndex ?: -1)?.let { store.markWatched(it.uri) }
                    ui.ended = true
                    ui.controls = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playing = isPlaying
            if (isPlaying) scheduleHide() else hideJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            lastError = error
            Log.e("AuroraTV", "Playback failed: ${error.errorCodeName}", error)
            ui.error = describe(error)
            ui.canSoftware = canOfferSoftware(error)
            ui.controls = false
        }
    }

    private fun tick() {
        val exo = player ?: return
        ui.position = exo.currentPosition.coerceAtLeast(0)
        ui.duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0
        ui.buffered = exo.bufferedPosition
        ui.live = exo.isCurrentMediaItemLive
        val remaining = ui.duration - ui.position
        val autoplay = !exo.pauseAtEndOfMediaItems
        ui.nextTitle = if (autoplay && exo.hasNextMediaItem() && ui.duration > 0 && remaining in 1..20_000) request.items.getOrNull(exo.nextMediaItemIndex)?.title else null
        if (exo.playbackState == Player.STATE_READY && System.currentTimeMillis() - lastSavedAt > 5_000) savePosition()
        if (exo.isPlaying) {
            playedMs += 500
            if (playedMs >= 60_000) {
                playedMs = 0
                aurora.settings.addWatchedMinute()
            }
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        val item = request.items.getOrNull(exo.currentMediaItemIndex) ?: return
        if (exo.playbackState == Player.STATE_ENDED) return
        lastSavedAt = System.currentTimeMillis()
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0
        store.save(item.uri, item.title, request.folder, exo.currentPosition, duration)
    }

    private fun updateItemInfo() {
        val exo = player ?: return
        val index = exo.currentMediaItemIndex
        ui.title = request.items.getOrNull(index)?.title.orEmpty()
        ui.subtitle = if (request.items.size > 1) "${index + 1} из ${request.items.size}" + (request.folder?.let { " · " + Uri.decode(it.trimEnd('/').substringAfterLast('/')) } ?: "") else null
        ui.hasPrevious = exo.hasPreviousMediaItem()
        ui.hasNext = exo.hasNextMediaItem()
    }

    // Controls visibility

    private fun showControls() {
        if (ui.error != null || ui.ended) return
        val wasHidden = !ui.controls
        ui.controls = true
        if (wasHidden) ui.focusTick++
        scheduleHide()
    }

    private fun hideControls() {
        ui.controls = false
        hideJob?.cancel()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        if (player?.isPlaying != true || ui.panel != null) return
        hideJob = lifecycleScope.launch {
            delay(5_000)
            if (player?.isPlaying == true && ui.panel == null) ui.controls = false
        }
    }

    private fun hint(text: String, durationMs: Long = 1_800) {
        ui.hint = text
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(durationMs)
            ui.hint = null
        }
    }

    // PlayerActions

    override fun userActivity() = scheduleHide()

    override fun togglePlay() {
        val exo = player ?: return
        if (lastError != null) retry() else if (exo.isPlaying) exo.pause() else exo.play()
    }

    override fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        var target = (exo.currentPosition + deltaMs).coerceAtLeast(0)
        if (exo.duration > 0) target = target.coerceAtMost(exo.duration - 1_000)
        exo.seekTo(target)
        ui.position = target
        hint((if (deltaMs < 0) "◀◀ " else "▶▶ ") + "${kotlin.math.abs(deltaMs) / 1000} с")
    }

    override fun previous() {
        val exo = player ?: return
        if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
            exo.play()
        }
    }

    override fun next() {
        val exo = player ?: return
        if (exo.hasNextMediaItem()) {
            ui.error = null
            lastError = null
            exo.seekToNextMediaItem()
            exo.prepare()
            exo.play()
        }
    }

    override fun exit() = finish()

    override fun retry() {
        val exo = player ?: return
        lastError = null
        ui.error = null
        exo.prepare()
        exo.play()
    }

    /** Error card action: retry the file with FFmpeg for the failed track type. */
    override fun softwareDecoder() {
        val manager = decoders ?: return
        val code = lastError?.errorCode ?: return
        if (code / 1000 == 5) {
            forceSoftwareAudio = true
            manager.selectAudioDecoder(DecoderMode.FFMPEG)
        } else {
            forceSoftwareVideo = true
            manager.selectVideoDecoder(DecoderMode.FFMPEG)
        }
        hint("Программный декодер FFmpeg")
        retry()
    }

    private fun canOfferSoftware(error: PlaybackException): Boolean {
        val group = error.errorCode / 1000
        return FfmpegLibrary.isAvailable() &&
            ((group == 4 && !forceSoftwareVideo) || (group == 5 && !forceSoftwareAudio))
    }

    private fun videoMode(preference: DecoderPreference) = when (preference) {
        DecoderPreference.AUTO -> DecoderMode.AUTO
        DecoderPreference.HARDWARE -> DecoderMode.HARDWARE
        DecoderPreference.SOFTWARE -> DecoderMode.FFMPEG
    }

    /** Audio stays automatic unless the TV-only mode is chosen: passthrough to the receiver must keep working. */
    private fun audioMode(preference: DecoderPreference) =
        if (preference == DecoderPreference.HARDWARE) DecoderMode.HARDWARE else DecoderMode.AUTO

    // Screen refresh rate

    @Suppress("DEPRECATION")
    private fun screen() = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay

    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || fps == matchedFps) return
        matchedFps = fps
        val display = screen() ?: return
        when (aurora.settings.current.frameRate) {
            FrameRateMode.ALWAYS -> RefreshRate.bestMode(display, fps)?.let { mode ->
                preferDisplayMode(mode.modeId)
                hint(String.format(Locale.ROOT, "Экран %.2f Гц под видео %.3f к/с", mode.refreshRate, fps), 3_000)
            }
            else -> if (!judderHintShown && RefreshRate.judders(display, fps)) {
                judderHintShown = true
                hint(String.format(Locale.ROOT, "Видео %.3f к/с на экране %.0f Гц может дёргаться · «Мой телевизор» → «Частота кадров»", fps, display.mode.refreshRate), 6_000)
            }
        }
    }

    private fun preferDisplayMode(modeId: Int) {
        val params = window.attributes
        if (params.preferredDisplayModeId == modeId) return
        params.preferredDisplayModeId = modeId
        window.attributes = params
    }

    override fun restart() {
        val exo = player ?: return
        ui.ended = false
        exo.seekTo(exo.currentMediaItemIndex, 0)
        exo.play()
        showControls()
    }

    override fun closePanel() {
        ui.panel = null
        ui.focusTick++
        scheduleHide()
    }

    private fun openPanel(panel: Panel) {
        hideJob?.cancel()
        ui.controls = true
        ui.panel = panel
    }

    override fun errorDetails() {
        val error = lastError ?: return
        val text = buildString {
            append("Код: ").append(error.errorCodeName).append('\n')
            append("Источник: ").append(request.items.getOrNull(player?.currentMediaItemIndex ?: 0)?.uri?.let { it.toUri().scheme } ?: "неизвестен").append("\n\n")
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth++ < 6) {
                append(cause.javaClass.simpleName)
                cause.message?.let { append(": ").append(it) }
                append('\n')
                cause = cause.cause
            }
            append("\nОшибка чтения — проверьте USB или соединение с сетевой папкой. Ошибка декодера — попробуйте другую дорожку или версию файла. ")
            append("Для отчёта укажите модель ТВ, Android, источник и этот текст; пароль от SMB не присылайте.")
        }
        ui.error = null
        openPanel(Panel.Info("Подробности ошибки", text))
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode / 1000) {
        2 -> "Не удалось прочитать файл: проверьте USB-накопитель, сеть или доступ к сетевой папке."
        3 -> "Файл повреждён или его формат не поддерживается."
        4 -> "Телевизор не смог декодировать это видео. Нажмите «Программный декодер» или выберите другую версию файла (например, H.264 вместо HEVC)."
        5 -> "Телевизор не смог воспроизвести эту звуковую дорожку. Нажмите «Программный декодер», выберите другую дорожку или включите PCM в настройках ТВ."
        else -> "Не удалось воспроизвести видео."
    } + " (${error.errorCodeName})"

    // Track menus

    private fun groups(type: Int): List<Tracks.Group> = player?.currentTracks?.groups.orEmpty().filter { it.type == type }

    override fun audio() {
        val exo = player ?: return
        val options = mutableListOf<PanelOption>()
        val custom = trackPrefs.getBoolean("audio_custom", false)
        options += PanelOption("Автоматически", "Вывод по настройкам ТВ", selected = !custom) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(null).setPreferredAudioLabels().build()
            trackPrefs.edit { putBoolean("audio_custom", false); remove("audio_language"); remove("audio_label"); remove("audio_mime"); remove("audio_channels"); remove("audio_index") }
            closePanel()
        }
        var ordinal = 0
        groups(C.TRACK_TYPE_AUDIO).forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val index = ordinal++
                options += PanelOption(
                    label = languageName(format.language) + (usable(format.label)?.let { " · $it" } ?: ""),
                    detail = listOfNotNull(
                        format.sampleMimeType?.removePrefix("audio/")?.uppercase(Locale.ROOT),
                        format.channelCount.takeIf { it > 0 }?.let { channelsLabel(it) },
                        format.bitrate.takeIf { it > 0 }?.let { "${it / 1000} кбит/с" },
                    ).joinToString(" · "),
                    selected = custom && group.isTrackSelected(i),
                ) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .setPreferredAudioLanguage(usable(format.language))
                        .apply { usable(format.label)?.let { setPreferredAudioLabels(it) } ?: setPreferredAudioLabels() }
                        .build()
                    trackPrefs.edit {
                        putBoolean("audio_custom", true)
                        putString("audio_language", usable(format.language))
                        putString("audio_label", usable(format.label))
                        putString("audio_mime", format.sampleMimeType)
                        putInt("audio_channels", format.channelCount)
                        putInt("audio_index", index)
                    }
                    closePanel()
                }
            }
        }
        if (options.size == 1) hint("Аудиодорожки ещё загружаются") else openPanel(Panel.Options("Аудиодорожка", options))
    }

    /** Re-applies the remembered audio track by label, language, codec and position. */
    private fun applySavedAudioTrack(tracks: Tracks) {
        val exo = player ?: return
        if (!trackPrefs.getBoolean("audio_custom", false)) return
        val language = trackPrefs.getString("audio_language", null)
        val label = trackPrefs.getString("audio_label", null)
        val mime = trackPrefs.getString("audio_mime", null)
        val channels = trackPrefs.getInt("audio_channels", -1)
        val savedIndex = trackPrefs.getInt("audio_index", -1)
        var ordinal = 0
        var bestScore = -1
        var best: TrackSelectionOverride? = null
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val labelMatch = label != null && label.equals(usable(format.label), ignoreCase = true)
                val languageMatch = language != null && language.equals(usable(format.language), ignoreCase = true)
                val indexFallback = language == null && label == null && ordinal == savedIndex
                if (labelMatch || languageMatch || indexFallback) {
                    val score = (if (labelMatch) 100 else 0) + (if (languageMatch) 30 else 0) +
                        (if (mime != null && mime == format.sampleMimeType) 5 else 0) +
                        (if (channels > 0 && channels == format.channelCount) 2 else 0) + (if (ordinal == savedIndex) 1 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        best = TrackSelectionOverride(group.mediaTrackGroup, i)
                    }
                }
                ordinal++
            }
        }
        val choice = best ?: return
        val current = exo.trackSelectionParameters.overrides[choice.mediaTrackGroup]
        if (current != null && current.trackIndices == choice.trackIndices) return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(choice).build()
    }

    private fun savedTrackParameters(exo: ExoPlayer) = exo.trackSelectionParameters.buildUpon().apply {
        val subtitles = trackPrefs.getBoolean("subtitles_enabled", true)
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
        setSelectTextByDefault(subtitles)
        setSelectUndeterminedTextLanguage(subtitles)
        trackPrefs.getString("subtitle_language", null)?.let { setPreferredTextLanguage(it) }
        trackPrefs.getString("audio_language", null)?.let { setPreferredAudioLanguage(it) }
        trackPrefs.getString("audio_label", null)?.let { setPreferredAudioLabels(it) }
    }.build()

    override fun subtitles() {
        val exo = player ?: return
        val enabled = !exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val anySelected = groups(C.TRACK_TYPE_TEXT).any { it.isSelected }
        val options = mutableListOf<PanelOption>()
        options += PanelOption("Выключить", selected = !enabled) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            trackPrefs.edit { putBoolean("subtitles_enabled", false); remove("subtitle_language") }
            closePanel()
        }
        options += PanelOption("Автоматически", "Встроенные субтитры по умолчанию", selected = enabled && !anySelected) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setSelectTextByDefault(true).setSelectUndeterminedTextLanguage(true)
                .setPreferredTextLanguages().build()
            trackPrefs.edit { putBoolean("subtitles_enabled", true); remove("subtitle_language") }
            closePanel()
        }
        groups(C.TRACK_TYPE_TEXT).forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                options += PanelOption(languageName(format.language), usable(format.label) ?: format.sampleMimeType?.substringAfter('/'), selected = enabled && group.isTrackSelected(i)) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .apply { format.language?.let { setPreferredTextLanguage(it) } }
                        .build()
                    trackPrefs.edit { putBoolean("subtitles_enabled", true); putString("subtitle_language", format.language) }
                    closePanel()
                }
            }
        }
        options += PanelOption("Открыть файл субтитров…", "SRT, VTT, ASS/SSA", icon = Icons.FileOpen) {
            closePanel()
            runCatching { pickSubtitle.launch(arrayOf("application/x-subrip", "text/plain", "text/vtt", "text/x-ssa", "application/octet-stream")) }
                .onFailure { hint("На ТВ нет выбора файлов") }
        }
        openPanel(Panel.Options("Субтитры", options))
    }

    private fun attachSubtitle(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = Media.displayName(this, uri).lowercase(Locale.ROOT)
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        externalSubtitle = subtitle
        trackPrefs.edit { putBoolean("subtitles_enabled", true) }
        val exo = player ?: return // applied in initializePlayer when the picker paused the player
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setSelectTextByDefault(true).build()
        val index = exo.currentMediaItemIndex
        val position = exo.currentPosition
        val current = exo.currentMediaItem ?: return
        exo.replaceMediaItem(index, current.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build())
        exo.seekTo(index, position)
        exo.prepare()
        exo.play()
        hint("Субтитры добавлены")
    }

    override fun settings() {
        val exo = player ?: return
        val speed = exo.playbackParameters.speed
        openPanel(Panel.Options("Настройки просмотра", listOf(
            PanelOption("Качество видео / HDR", "Выбор дорожки, если поток их предлагает", icon = Icons.Hdr) { videoMenu() },
            PanelOption("Формат изображения", resizeLabels[resizeModes.indexOf(resizeMode).coerceAtLeast(0)], icon = Icons.AspectRatio) { resizeMenu() },
            PanelOption("Скорость", speedLabel(speed), icon = Icons.Speed) { speedMenu() },
            PanelOption("Повтор файла", if (exo.repeatMode == Player.REPEAT_MODE_ONE) "Включён" else "Выключен", icon = Icons.Repeat) {
                exo.repeatMode = if (exo.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                hint(if (exo.repeatMode == Player.REPEAT_MODE_ONE) "Повтор включён" else "Повтор выключен")
                closePanel()
            },
            PanelOption("Подсветка Ambilight", when (AmbientLighting.isEnabled(this)) {
                null -> "Управляется телевизором"
                true -> "Включена"
                false -> "Выключена"
            }, icon = Icons.Lightbulb) { ambientMenu() },
            PanelOption("Информация о декодерах", "Поток, декодер, возможности ТВ", icon = Icons.Info) { decoderInfo() },
        )))
    }

    private fun videoMenu() {
        val exo = player ?: return
        val options = mutableListOf(PanelOption("Автоматически", "Лучшее совместимое качество", selected = exo.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            closePanel()
        })
        groups(C.TRACK_TYPE_VIDEO).forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                options += PanelOption(
                    label = if (f.width > 0) "${f.width}×${f.height}" else "Видео",
                    detail = listOfNotNull(f.frameRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.0f fps", it) }, dynamicRange(f), f.sampleMimeType?.removePrefix("video/")).joinToString(" · "),
                    selected = group.isTrackSelected(i) && exo.trackSelectionParameters.overrides.containsKey(group.mediaTrackGroup),
                ) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    closePanel()
                }
            }
        }
        options += PanelOption("Когда можно выбрать HDR или SDR?", icon = Icons.Info) {
            openPanel(Panel.Info("HDR и SDR", "HDR10, HDR10+, HLG и Dolby Vision включаются автоматически, когда файл, системный декодер и экран совместимы. " +
                "Если поток предлагает отдельные HDR- и SDR-дорожки, нужную можно выбрать здесь. У одиночного HDR-файла нельзя выключить HDR без отдельной SDR-версии; " +
                "плеер не меняет настройки изображения ТВ."))
        }
        openPanel(Panel.Options("Качество видео / HDR", options))
    }

    private val resizeModes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    private val resizeLabels = listOf("По размеру — без обрезки", "Заполнить экран — с обрезкой", "Растянуть — пропорции меняются")

    private fun resizeMenu() = openPanel(Panel.Options("Формат изображения", resizeModes.mapIndexed { i, mode ->
        PanelOption(resizeLabels[i], selected = mode == resizeMode) {
            resizeMode = mode
            playerView.resizeMode = mode
            trackPrefs.edit { putInt("resize_mode", mode) }
            closePanel()
        }
    } + PanelOption("720p на 4K-телевизоре", icon = Icons.Info) {
        openPanel(Panel.Info("Масштабирование", "Видео автоматически масштабируется до экрана ТВ. Файл 720p остаётся 720p по детализации; " +
            "промежуточное разрешение не добавит деталей. Чёткость и шумоподавление настраиваются в меню изображения телевизора."))
    }))

    private fun speedMenu() {
        val exo = player ?: return
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        openPanel(Panel.Options("Скорость", speeds.map { s ->
            PanelOption(speedLabel(s), if (s == 1f) "Рекомендуется для оптики и Pass-through" else null, selected = kotlin.math.abs(exo.playbackParameters.speed - s) < 0.01f) {
                exo.setPlaybackSpeed(s)
                closePanel()
            }
        }))
    }

    /** 1 -> "1×", 1.5 -> "1,5×", 0.75 -> "0,75×". */
    private fun speedLabel(speed: Float) = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().replace('.', ',')) + "×"

    private fun ambientMenu() {
        val enabled = AmbientLighting.isEnabled(this)
        if (enabled == null) {
            openPanel(Panel.Info("Подсветка экрана", "На этом телевизоре плееру недоступен системный переключатель подсветки. На Philips со своей " +
                "Ambilight режим задаётся самим ТВ; плеер выводит обычное видео и не вмешивается в обработку цвета и HDR. " +
                "Общий API подсветки появился в Android TV 16 и работает, только если его реализовал производитель."))
            return
        }
        openPanel(Panel.Options("Системная подсветка", listOf(
            PanelOption("Включить", selected = enabled) { toggleAmbient(true) },
            PanelOption("Выключить", selected = !enabled) { toggleAmbient(false) },
        )))
    }

    private fun toggleAmbient(on: Boolean) {
        hint(if (AmbientLighting.setEnabled(this, on)) (if (on) "Подсветка включена" else "Подсветка выключена") else "ТВ не подтвердил изменение")
        closePanel()
    }

    private fun decoderInfo() {
        val exo = player
        val text = buildString {
            exo?.videoFormat?.let { f ->
                append("Сейчас: ${f.width}×${f.height}")
                if (f.frameRate > 0) append(String.format(Locale.ROOT, " · %.2f fps", f.frameRate))
                append(" · ").append(f.sampleMimeType ?: "?").append(" · ").append(dynamicRange(f))
                f.codecs?.let { append(" · ").append(it) }
                append('\n')
            }
            exo?.audioFormat?.let { f ->
                append("Звук: ").append(f.sampleMimeType ?: "?")
                if (f.channelCount > 0) append(" · ").append(channelsLabel(f.channelCount))
                append('\n')
            }
            append("Видеодекодер: ").append(videoDecoder?.let { it + acceleration(it) } ?: "ещё не запущен").append('\n')
            append("Аудиодекодер: ").append(audioDecoder ?: "ещё не запущен").append('\n')
            decoders?.let { append("Режим: видео ").append(modeName(it.activeVideoMode)).append(", звук ").append(modeName(it.activeAudioMode)).append('\n') }
            append("FFmpeg: ").append(if (FfmpegLibrary.isAvailable()) "доступен, версия ${FfmpegLibrary.getVersion()}" else "недоступен").append("\n\n")
            append("Аппаратные декодеры 4K/60:\n")
            listOf("H.264" to "video/avc", "HEVC" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9", "AV1" to "video/av01", "Dolby Vision" to "video/dolby-vision").forEach { (name, mime) ->
                append("• ").append(name).append(": ").append(codecSupport(mime)).append('\n')
            }
            append("\nЭто возможности, заявленные прошивкой, а не гарантия плавности каждого файла. Наличие аудиодекодера не означает передачу формата через оптику.")
        }
        openPanel(Panel.Info("Устройство и поток", text))
    }

    private fun codecSupport(mime: String): String {
        var found = false
        var hardware = false
        var uhd = false
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }.forEach { codec ->
                found = true
                val hw = Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated
                hardware = hardware || hw
                val caps = runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                if (caps != null && runCatching { caps.areSizeAndRateSupported(3840, 2160, 60.0) }.getOrDefault(false) && (hw || Build.VERSION.SDK_INT < 29)) uhd = true
            }
        }
        return when {
            !found -> "нет"
            uhd -> "4K/60 заявлен"
            hardware -> "аппаратный, без 4K/60"
            else -> "есть"
        }
    }

    private fun acceleration(name: String): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        val codec: MediaCodecInfo? = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == name } }.getOrNull()
        return when (codec?.isHardwareAccelerated) {
            true -> " (аппаратный)"
            false -> " (программный)"
            null -> ""
        }
    }

    private fun modeName(mode: DecoderMode?) = when (mode) {
        null -> "ещё не выбран"
        DecoderMode.AUTO -> "авто"
        DecoderMode.HARDWARE -> "аппаратный"
        DecoderMode.SOFTWARE -> "программный Android"
        DecoderMode.FFMPEG -> "FFmpeg"
    }

    private fun dynamicRange(f: Format): String = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10/10+"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SDR || f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_SRGB -> "SDR"
        else -> "SDR/HDR не указан"
    }

    private fun usable(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }

    private fun languageName(code: String?): String = usable(code)?.let {
        Locale.forLanguageTag(it).getDisplayLanguage(RUSSIAN).replaceFirstChar { c -> c.titlecase(RUSSIAN) }.ifEmpty { it }
    } ?: "Язык не указан"

    private fun channelsLabel(count: Int) = when (count) {
        1 -> "моно"
        2 -> "стерео"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count кан."
    }

    // Remote

    private companion object {
        val RUSSIAN: Locale = Locale.forLanguageTag("ru")
    }

    @SuppressLint("RestrictedApi") // ComponentActivity marks the override restricted; overriding it is how keys are seen first.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)
        val key = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.repeatCount == 0) when {
                ui.panel != null -> closePanel()
                ui.controls && ui.error == null && !ui.ended -> hideControls()
                else -> finish()
            }
            return true
        }
        if (!down) return super.dispatchKeyEvent(event)
        when (key) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-ui.backStepMs); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(ui.forwardStepMs); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> { next(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> { previous(); showControls(); return true }
        }
        if (!ui.controls && ui.error == null && !ui.ended) {
            // Hidden controls: ←/→ jump right away, other keys bring the panel up.
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-ui.backStepMs * (1 + event.repeatCount / 4)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(ui.forwardStepMs * (1 + event.repeatCount / 4)); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
            }
        }
        userActivity()
        return super.dispatchKeyEvent(event)
    }
}
