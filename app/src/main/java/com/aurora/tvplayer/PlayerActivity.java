package com.aurora.tvplayer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final int PICK_SUBTITLE = 31;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refreshProgress(); handler.postDelayed(this, 500); }
    };
    private final Runnable hide = () -> {
        if (this.controls != null && this.player != null && this.player.isPlaying() && this.lastError == null)
            this.controls.setVisibility(View.GONE);
    };
    private ArrayList<String> uris;
    private ArrayList<String> titles;
    private int startIndex;
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout controls;
    private TextView titleView, timeView, statusView;
    private Button playButton;
    private Button previousButton, nextButton, retryButton;
    private SeekBar seekBar;
    private SharedPreferences resume;
    private String currentUri;
    private PlaybackException lastError;
    private String currentVideoDecoder;
    private String currentAudioDecoder;
    private long lastSavedAt;
    private Uri externalSubtitle;
    private String externalSubtitleMime;
    private final int[] resizeModes = {AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL};

    private interface ChoiceHandler { void choose(int index); }

    private void showChoiceDialog(String heading, List<String> choices, ChoiceHandler handler) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.handler.removeCallbacks(hide);
        dialog.setOnDismissListener(ignored -> scheduleHide());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(TvUi.dp(this, 22), TvUi.dp(this, 16), TvUi.dp(this, 22), TvUi.dp(this, 16));
        panel.setBackground(TvUi.shape(TvUi.PANEL, TvUi.dp(this, 16)));
        TextView title = TvUi.text(this, heading, 21, TvUi.TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(items);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1,
            Math.min(TvUi.dp(this, 330), TvUi.dp(this, Math.max(1, choices.size()) * 46)));
        scrollParams.topMargin = TvUi.dp(this, 10);
        panel.addView(scroll, scrollParams);
        TextView first = null;
        for (int i = 0; i < choices.size(); i++) {
            final int index = i;
            TextView row = TvUi.text(this, choices.get(i), 16, TvUi.TEXT);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(TvUi.dp(this, 16), 0, TvUi.dp(this, 16), 0);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setBackground(TvUi.shape(0xff192e3b, TvUi.dp(this, 8)));
            row.setOnFocusChangeListener((v, focused) -> {
                row.setBackground(TvUi.shape(focused ? TvUi.ACCENT : 0xff192e3b, TvUi.dp(this, 8)));
                row.setTextColor(focused ? TvUi.BG : TvUi.TEXT);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42));
            rp.bottomMargin = TvUi.dp(this, 4);
            items.addView(row, rp);
            row.setOnClickListener(v -> { dialog.dismiss(); handler.choose(index); });
            if (first == null) first = row;
            if (choices.get(i).contains("✓")) first = row;
        }
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        if (window != null) window.setLayout(Math.min(TvUi.dp(this, 570),
            getResources().getDisplayMetrics().widthPixels - TvUi.dp(this, 100)), -2);
        if (first != null) first.requestFocus();
    }

    private void showInfoDialog(String heading, String message) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        handler.removeCallbacks(hide);
        dialog.setOnDismissListener(ignored -> scheduleHide());
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(TvUi.dp(this, 22), TvUi.dp(this, 16), TvUi.dp(this, 22), TvUi.dp(this, 16));
        panel.setBackground(TvUi.shape(TvUi.PANEL, TvUi.dp(this, 16)));
        TextView title = TvUi.text(this, heading, 21, TvUi.TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);
        ScrollView scroll = new ScrollView(this);
        TextView body = TvUi.text(this, message, 15, TvUi.TEXT);
        body.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 8));
        scroll.addView(body);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 280));
        sp.topMargin = TvUi.dp(this, 8);
        panel.addView(scroll, sp);
        Button close = TvUi.button(this, "Закрыть");
        close.setTextSize(14);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 40));
        bp.topMargin = TvUi.dp(this, 8);
        panel.addView(close, bp);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        if (window != null) window.setLayout(Math.min(TvUi.dp(this, 620),
            getResources().getDisplayMetrics().widthPixels - TvUi.dp(this, 100)), -2);
        close.requestFocus();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        uris = getIntent().getStringArrayListExtra("uris");
        titles = getIntent().getStringArrayListExtra("titles");
        if (uris == null || uris.isEmpty()) { finish(); return; }
        if (titles == null || titles.size() != uris.size()) {
            titles = new ArrayList<>();
            for (String uri : uris) titles.add(VideoLibrary.displayName(this, Uri.parse(uri)));
        }
        startIndex = Math.max(0, Math.min(getIntent().getIntExtra("index", 0), uris.size() - 1));
        resume = getSharedPreferences("library", MODE_PRIVATE);
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        if (uris == null || uris.isEmpty() || player != null) return;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true);
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(this, renderers);
        boolean hasSmb = false;
        for (String uri : uris) if ("smb".equalsIgnoreCase(Uri.parse(uri).getScheme())) hasSmb = true;
        if (hasSmb) {
            DataSource.Factory source = () -> new DefaultDataSource(this,
                SmbDataSource.FACTORY.createDataSource());
            playerBuilder.setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(source));
        }
        player = playerBuilder.build();
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                    long initializedTimestampMs, long initializationDurationMs) {
                currentVideoDecoder = decoderName;
            }
            @Override public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
                if (decoderName.equals(currentVideoDecoder)) currentVideoDecoder = null;
            }
            @Override public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                    long initializedTimestampMs, long initializationDurationMs) {
                currentAudioDecoder = decoderName;
            }
            @Override public void onAudioDecoderReleased(EventTime eventTime, String decoderName) {
                if (decoderName.equals(currentAudioDecoder)) currentAudioDecoder = null;
            }
        });
        boolean subtitlesEnabled = resume.getBoolean("subtitles_enabled", true);
        TrackSelectionParameters.Builder savedText = player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setSelectTextByDefault(subtitlesEnabled)
            .setSelectUndeterminedTextLanguage(subtitlesEnabled);
        String subtitleLanguage = resume.getString("subtitle_language", null);
        if (subtitleLanguage != null) savedText.setPreferredTextLanguage(subtitleLanguage);
        String audioLanguage = resume.getString("audio_language", null);
        String audioLabel = resume.getString("audio_label", null);
        if (audioLanguage != null) savedText.setPreferredAudioLanguage(audioLanguage);
        if (audioLabel != null) savedText.setPreferredAudioLabels(audioLabel);
        player.setTrackSelectionParameters(savedText.build());
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                currentUri = item == null ? null : item.localConfiguration.uri.toString();
                int i = player.getCurrentMediaItemIndex();
                if (i >= 0 && i < titles.size()) {
                    titleView.setText(titles.get(i));
                    resume.edit().putString("recent_uri", uris.get(i)).putString("recent_title", titles.get(i)).apply();
                }
                updateEpisodeButtons();
                if (resume.getBoolean("audio_custom", false)) {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
                }
                showControls();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                applySavedAudioTrack(tracks);
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        && oldPosition.mediaItemIndex >= 0 && oldPosition.mediaItemIndex < uris.size()) {
                    resume.edit().remove(positionKey(uris.get(oldPosition.mediaItemIndex))).apply();
                }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) { statusView.setText("Загрузка…"); statusView.setVisibility(View.VISIBLE); }
                else if (state == Player.STATE_READY) {
                    lastError = null;
                    statusView.setVisibility(View.GONE);
                    retryButton.setVisibility(View.GONE);
                }
                else if (state == Player.STATE_ENDED) {
                    statusView.setText("Воспроизведение завершено");
                    statusView.setVisibility(View.VISIBLE);
                    if (currentUri != null) resume.edit().remove(positionKey(currentUri)).apply();
                    showControls();
                }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                playButton.setText(playing ? "❚❚  Пауза" : "▶  Играть");
                if (playing) scheduleHide();
                else { handler.removeCallbacks(hide); showControls(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                lastError = error;
                Log.e("AuroraTV", "Playback failed: " + error.getErrorCodeName(), error);
                statusView.setText(errorSummary(error));
                statusView.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                showControls();
            }
        });
        List<MediaItem> items = new ArrayList<>();
        for (String uri : uris) items.add(new MediaItem.Builder().setUri(uri).build());
        long saved = resume.getLong(positionKey(uris.get(startIndex)), 0);
        player.setMediaItems(items, startIndex, saved);
        currentUri = uris.get(startIndex);
        titleView.setText(titles.get(startIndex));
        player.prepare();
        player.play();
        handler.post(tick);
        showControls();
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xff000000);
        setContentView(frame);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        frame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(TvUi.dp(this, 28), TvUi.dp(this, 10), TvUi.dp(this, 28), TvUi.dp(this, 12));
        controls.setBackgroundColor(0xd6080e17);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        frame.addView(controls, cp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(info);
        titleView = TvUi.text(this, "Видео", 18, TvUi.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        timeView = TvUi.text(this, "00:00 / --:--", 14, TvUi.MUTED);
        info.addView(timeView);
        statusView = TvUi.text(this, "", 13, TvUi.ACCENT);
        statusView.setVisibility(View.GONE);
        statusView.setFocusable(true);
        statusView.setOnClickListener(v -> showLastError());
        controls.addView(statusView);
        seekBar = new SeekBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 34));
        sp.topMargin = TvUi.dp(this, 3);
        controls.addView(seekBar, sp);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { handler.removeCallbacks(hide); }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0)
                    timeView.setText(formatTime(player.getDuration() * value / 1000) + " / " + formatTime(player.getDuration()));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && player.getDuration() > 0) player.seekTo(player.getDuration() * bar.getProgress() / 1000);
                scheduleHide();
            }
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42));
        bp.topMargin = TvUi.dp(this, 4);
        controls.addView(buttons, bp);
        previousButton = addButton(buttons, "◀◀ Серия", this::previousEpisode);
        addButton(buttons, "−10 с", () -> seekRelative(-10_000));
        playButton = addButton(buttons, "❚❚ Пауза", () -> {
            if (player == null) return;
            if (lastError != null) retryPlayback();
            else if (player.isPlaying()) player.pause();
            else player.play();
        });
        addButton(buttons, "+30 с", () -> seekRelative(30_000));
        nextButton = addButton(buttons, "Серия ▶▶", this::nextEpisode);
        addButton(buttons, "↩ К файлам", this::finish);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 36));
        optionsParams.topMargin = TvUi.dp(this, 5);
        controls.addView(options, optionsParams);
        addButton(options, "Аудио", this::showTrackMenu);
        addButton(options, "Субтитры", this::showSubtitleMenu);
        addButton(options, "Настройки · ⋯", this::showMoreMenu);
        retryButton = addButton(options, "↻ Повторить", this::retryPlayback);
        retryButton.setVisibility(View.GONE);
        playButton.post(playButton::requestFocus);
    }

    private Button addButton(LinearLayout row, String label, Runnable action) {
        Button button = TvUi.button(this, label);
        button.setTextSize(14);
        button.setPadding(TvUi.dp(this, 4), 0, TvUi.dp(this, 4), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.rightMargin = TvUi.dp(this, 6);
        row.addView(button, lp);
        button.setOnClickListener(v -> { action.run(); scheduleHide(); });
        return button;
    }

    private void updateEpisodeButtons() {
        if (player == null || previousButton == null || nextButton == null) return;
        previousButton.setEnabled(player.hasPreviousMediaItem());
        nextButton.setEnabled(player.hasNextMediaItem());
        previousButton.setAlpha(previousButton.isEnabled() ? 1f : 0.45f);
        nextButton.setAlpha(nextButton.isEnabled() ? 1f : 0.45f);
    }

    private void previousEpisode() {
        if (player != null && player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem();
            player.play();
        }
    }

    private void nextEpisode() {
        if (player != null && player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
            player.play();
        }
    }

    private void retryPlayback() {
        if (player == null) return;
        lastError = null;
        statusView.setText("Пробую открыть файл снова…");
        retryButton.setVisibility(View.GONE);
        player.prepare();
        player.play();
    }

    private void refreshProgress() {
        if (player == null) return;
        long duration = player.getDuration();
        long position = Math.max(0, player.getCurrentPosition());
        if (!seekBar.isPressed()) {
            seekBar.setProgress(duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0);
            timeView.setText(formatTime(position) + " / " + (duration > 0 ? formatTime(duration)
                : player.isCurrentMediaItemLive() ? "LIVE" : "--:--"));
        }
        if (currentUri != null && position > 5000 && player.getPlaybackState() == Player.STATE_READY
                && android.os.SystemClock.elapsedRealtime() - lastSavedAt > 5000) {
            resume.edit().putLong(positionKey(currentUri), position).apply();
            lastSavedAt = android.os.SystemClock.elapsedRealtime();
        }
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0, ms / 1000);
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String positionKey(String uri) { return "position_" + uri.hashCode(); }

    private static String errorSummary(PlaybackException error) {
        String category;
        if (error.errorCode >= 2000 && error.errorCode < 3000)
            category = "Не удалось прочитать файл или сеть";
        else if (error.errorCode >= 3000 && error.errorCode < 4000)
            category = "Не удалось разобрать контейнер или поток";
        else if (error.errorCode >= 4000 && error.errorCode < 5000)
            category = "Декодер ТВ не запустил дорожку";
        else category = "Не удалось воспроизвести";
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String detail = cause.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getErrorCodeName();
        if (detail.length() > 115) detail = detail.substring(0, 112) + "…";
        return category + ": " + detail + "  ·  нажмите для подробностей";
    }

    private void showLastError() {
        if (lastError == null) return;
        StringBuilder details = new StringBuilder();
        details.append("Код: ").append(lastError.getErrorCodeName()).append('\n');
        details.append("Источник: ").append(currentUri == null ? "неизвестен"
            : Uri.parse(currentUri).getScheme()).append("\n\n");
        Throwable cause = lastError;
        for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause()) {
            details.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) details.append(": ").append(cause.getMessage());
            details.append('\n');
        }
        details.append("\nЕсли ошибка чтения — проверьте доступ к USB или соединение SMB. "
            + "Если ошибка декодера — попробуйте другую видеодорожку/версию файла. "
            + "Для отчёта укажите модель ТВ, Android, источник и этот текст; пароль от SMB не присылайте.");
        showInfoDialog("Ошибка воспроизведения", details.toString());
    }

    private void savePosition() {
        if (player == null || currentUri == null) return;
        long position = player.getCurrentPosition();
        if (position > 5000 && player.getPlaybackState() != Player.STATE_ENDED)
            resume.edit().putLong(positionKey(currentUri), position).apply();
    }

    private void seekRelative(long delta) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (player.getDuration() > 0) target = Math.min(target, player.getDuration());
        player.seekTo(target);
        showControls();
    }

    private void showControls() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hide);
        if (player != null && player.isPlaying() && lastError == null)
            handler.postDelayed(hide, 7000);
    }

    private void showTrackMenu() {
        if (player == null) return;
        List<Tracks.Group> groups = collectGroups(C.TRACK_TYPE_AUDIO);
        if (groups.isEmpty()) {
            Toast.makeText(this, "Аудиодорожки ещё загружаются или недоступны", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> choices = new ArrayList<>();
        List<TrackSelectionOverride> overrides = new ArrayList<>();
        List<Format> formats = new ArrayList<>();
        choices.add("Автоматически · вывод по настройкам ТВ");
        for (Tracks.Group group : groups) {
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                String language = f.language == null ? "Язык не указан" :
                    Locale.forLanguageTag(f.language).getDisplayLanguage(new Locale("ru"));
                String codec = f.sampleMimeType == null ? "" : " · " + f.sampleMimeType.replace("audio/", "").toUpperCase(Locale.ROOT);
                String channels = f.channelCount > 0 ? " · " + f.channelCount + " кан." : "";
                String label = f.label == null || f.label.isEmpty() ? "" : " · " + f.label;
                String bitrate = f.bitrate > 0 ? " · " + (f.bitrate / 1000) + " кбит/с" : "";
                choices.add(language + codec + channels + label + bitrate + (group.isTrackSelected(i) ? "  ✓" : ""));
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
                formats.add(f);
            }
        }
        showChoiceDialog("Аудиодорожка", choices, choice -> {
                TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                SharedPreferences.Editor edit = resume.edit();
                if (choice > 0) {
                    Format selected = formats.get(choice - 1);
                    String language = usableMetadata(selected.language);
                    String label = usableMetadata(selected.label);
                    builder.addOverride(overrides.get(choice - 1))
                        .setPreferredAudioLanguage(language);
                    if (label != null) builder.setPreferredAudioLabels(label);
                    else builder.setPreferredAudioLabels();
                    edit.putBoolean("audio_custom", true).putString("audio_language", language)
                        .putString("audio_label", label).putString("audio_mime", selected.sampleMimeType)
                        .putInt("audio_channels", selected.channelCount).putInt("audio_index", choice - 1);
                } else {
                    builder.setPreferredAudioLanguage(null).setPreferredAudioLabels();
                    edit.putBoolean("audio_custom", false).remove("audio_language")
                        .remove("audio_label").remove("audio_mime")
                        .remove("audio_channels").remove("audio_index");
                }
                edit.apply();
                player.setTrackSelectionParameters(builder.build());
            });
    }

    private static String usableMetadata(String value) {
        if (value == null || value.trim().isEmpty() || "und".equalsIgnoreCase(value)) return null;
        return value.trim();
    }

    private void applySavedAudioTrack(Tracks tracks) {
        if (player == null || !resume.getBoolean("audio_custom", false)) return;
        String language = resume.getString("audio_language", null);
        String label = resume.getString("audio_label", null);
        String mime = resume.getString("audio_mime", null);
        int channels = resume.getInt("audio_channels", -1);
        int savedIndex = resume.getInt("audio_index", -1);
        int ordinal = 0;
        int bestScore = -1;
        TrackSelectionOverride best = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                boolean labelMatch = label != null && label.equalsIgnoreCase(usableMetadata(format.label));
                boolean languageMatch = language != null && language.equalsIgnoreCase(usableMetadata(format.language));
                boolean fallbackIndex = language == null && label == null && ordinal == savedIndex;
                if (!labelMatch && !languageMatch && !fallbackIndex) { ordinal++; continue; }
                int score = (labelMatch ? 100 : 0) + (languageMatch ? 30 : 0)
                    + (mime != null && mime.equals(format.sampleMimeType) ? 5 : 0)
                    + (channels > 0 && channels == format.channelCount ? 2 : 0)
                    + (ordinal == savedIndex ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = new TrackSelectionOverride(group.getMediaTrackGroup(), i);
                }
                ordinal++;
            }
        }
        if (best == null) return;
        TrackSelectionOverride current = player.getTrackSelectionParameters().overrides.get(best.mediaTrackGroup);
        if (current != null && current.trackIndices.equals(best.trackIndices)) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(best).build());
    }

    private void showVideoMenu() {
        if (player == null) return;
        if (collectGroups(C.TRACK_TYPE_VIDEO).isEmpty()) {
            Toast.makeText(this, "Видеодорожки ещё загружаются или недоступны", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> choices = new ArrayList<>();
        List<TrackSelectionOverride> overrides = new ArrayList<>();
        choices.add("Автоматически · лучшее совместимое качество");
        for (Tracks.Group group : collectGroups(C.TRACK_TYPE_VIDEO)) {
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                StringBuilder label = new StringBuilder();
                if (f.width > 0 && f.height > 0) label.append(f.width).append('×').append(f.height);
                else label.append("Видео");
                if (f.frameRate > 0) label.append(String.format(Locale.ROOT, " · %.1f fps", f.frameRate));
                label.append(" · ").append(dynamicRange(f));
                if (f.sampleMimeType != null) label.append(" · ").append(f.sampleMimeType.replace("video/", ""));
                if (group.isTrackSelected(i)) label.append("  ✓");
                choices.add(label.toString());
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        choices.add("Когда можно выбрать HDR или SDR?");
        int help = choices.size() - 1;
        showChoiceDialog("Качество видео / HDR", choices, choice -> {
            if (choice == help) {
                showInfoDialog("HDR и SDR", "Режим Auto воспроизводит HDR10, HDR10+, HLG или Dolby Vision "
                    + "автоматически, когда файл, системный декодер и экран ТВ совместимы. "
                    + "Если поток предлагает отдельные HDR- и SDR-дорожки, выберите нужную здесь. "
                    + "У одиночного HDR-файла нельзя просто выключить HDR без отдельной SDR-версии "
                    + "или тонального преобразования; плеер не подменяет настройки изображения ТВ.");
                return;
            }
            TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false);
            if (choice > 0) builder.addOverride(overrides.get(choice - 1));
            player.setTrackSelectionParameters(builder.build());
        });
    }

    private static String dynamicRange(Format format) {
        if ("video/dolby-vision".equals(format.sampleMimeType)) return "Dolby Vision";
        if (format.colorInfo == null) return "HDR/SDR не указан";
        if (format.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG";
        if (format.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) return "PQ · HDR10/10+ не различены";
        if (format.colorInfo.colorTransfer == C.COLOR_TRANSFER_SDR
                || format.colorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB) return "SDR";
        return "HDR/SDR не указан";
    }

    private void showSubtitleMenu() {
        if (player == null) return;
        List<Tracks.Group> groups = collectGroups(C.TRACK_TYPE_TEXT);
        List<String> choices = new ArrayList<>();
        List<TrackSelectionOverride> overrides = new ArrayList<>();
        choices.add("Выключить");
        choices.add("Автоматически");
        for (Tracks.Group group : groups) {
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                choices.add((f.language == null ? "Субтитры" : f.language)
                    + (f.label == null || f.label.isEmpty() ? "" : " · " + f.label)
                    + (group.isTrackSelected(i) ? "  ✓" : ""));
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
            }
        }
        choices.add("Открыть файл субтитров…");
        int externalChoice = choices.size() - 1;
        showChoiceDialog("Субтитры", choices, choice -> {
                if (choice == externalChoice) { pickSubtitle(); return; }
                TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice == 0)
                    .setSelectTextByDefault(choice != 0).setSelectUndeterminedTextLanguage(choice != 0);
                if (choice >= 2) {
                    builder.addOverride(overrides.get(choice - 2));
                    Format chosen = collectSubtitleFormats().get(choice - 2);
                    String language = chosen.language;
                    resume.edit().putBoolean("subtitles_enabled", true)
                        .putString("subtitle_language", language).apply();
                    if (language != null) builder.setPreferredTextLanguage(language);
                } else {
                    resume.edit().putBoolean("subtitles_enabled", choice != 0)
                        .remove("subtitle_language").apply();
                    builder.setPreferredTextLanguages();
                }
                player.setTrackSelectionParameters(builder.build());
            });
    }

    private List<Format> collectSubtitleFormats() {
        List<Format> result = new ArrayList<>();
        for (Tracks.Group group : collectGroups(C.TRACK_TYPE_TEXT))
            for (int i = 0; i < group.length; i++)
                if (group.isTrackSupported(i)) result.add(group.getTrackFormat(i));
        return result;
    }

    private List<Tracks.Group> collectGroups(int type) {
        List<Tracks.Group> result = new ArrayList<>();
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) if (group.getType() == type) result.add(group);
        return result;
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-subrip", "text/plain", "text/vtt", "text/x-ssa", "application/octet-stream"});
        startActivityForResult(intent, PICK_SUBTITLE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SUBTITLE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = VideoLibrary.displayName(this, uri).toLowerCase(Locale.ROOT);
        String mime = name.endsWith(".vtt") ? MimeTypes.TEXT_VTT :
            (name.endsWith(".ass") || name.endsWith(".ssa")) ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        externalSubtitle = uri;
        externalSubtitleMime = mime;
        resume.edit().putBoolean("subtitles_enabled", true).apply();
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setSelectTextByDefault(true).build());
        int index = player.getCurrentMediaItemIndex();
        long position = player.getCurrentPosition();
        MediaItem old = player.getCurrentMediaItem();
        if (old == null) return;
        MediaItem.SubtitleConfiguration subtitle = new MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
        MediaItem updated = old.buildUpon().setSubtitleConfigurations(java.util.Collections.singletonList(subtitle)).build();
        player.replaceMediaItem(index, updated);
        player.seekTo(index, position);
        player.prepare();
        player.play();
        Toast.makeText(this, "Субтитры добавлены", Toast.LENGTH_SHORT).show();
    }

    private void showMoreMenu() {
        String[] options = {"Следующее видео", "Предыдущее видео", "Качество видео / HDR", "Формат изображения", "Скорость воспроизведения", "Повтор" + (player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "  ✓" : ""), "Подсветка Ambilight", "Информация о декодерах", "Последняя ошибка"};
        showChoiceDialog("Настройки просмотра", java.util.Arrays.asList(options), index -> {
                if (player == null) return;
                switch (index) {
                    case 0: if (player.hasNextMediaItem()) nextEpisode();
                        else Toast.makeText(this, "Следующего видео в этой папке нет", Toast.LENGTH_SHORT).show();
                        break;
                    case 1: if (player.hasPreviousMediaItem()) previousEpisode();
                        else Toast.makeText(this, "Предыдущего видео в этой папке нет", Toast.LENGTH_SHORT).show();
                        break;
                    case 2: showVideoMenu(); break;
                    case 3: showResizeMenu(); break;
                    case 4: showSpeedMenu(); break;
                    case 5:
                        player.setRepeatMode(player.getRepeatMode() == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                        Toast.makeText(this, player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "Повтор включён" : "Повтор выключен", Toast.LENGTH_SHORT).show();
                        break;
                    case 6: showAmbientLightingMenu(); break;
                    case 7: showDecoderInfo(); break;
                    case 8: if (lastError != null) showLastError();
                        else Toast.makeText(this, "Ошибок воспроизведения нет", Toast.LENGTH_SHORT).show();
                        break;
                }
            });
    }

    private void showAmbientLightingMenu() {
        Boolean enabled = AmbientLighting.isEnabled(this);
        if (enabled == null) {
            showInfoDialog("Подсветка экрана", "На этом устройстве плееру недоступен системный "
                + "переключатель Ambilight. На Philips с собственной подсветкой её режим "
                + "задаётся самим телевизором; при воспроизведении плеер выводит обычное видео "
                + "через SurfaceView, не вмешиваясь в обработку цвета и HDR. "
                + "Android TV 16 предлагает общий API подсветки, но только если его "
                + "реализовал производитель ТВ. Samsung Tizen и LG webOS не запускают "
                + "Android-приложения напрямую.");
            return;
        }
        List<String> choices = new ArrayList<>();
        choices.add("Включить подсветку" + (enabled ? "  ✓" : ""));
        choices.add("Выключить подсветку" + (!enabled ? "  ✓" : ""));
        choices.add("Как работает подсветка");
        showChoiceDialog("Системная подсветка", choices, choice -> {
            if (choice == 2) {
                showInfoDialog("Подсветка экрана", "Плеер обращается к системному API Android TV 16. "
                    + "Цвета сцен и плавность реакции рассчитывает прошивка телевизора, "
                    + "а не приложение. Настройка может действовать и вне плеера. "
                    + "Если ТВ не поддерживает этот API, переключатель недоступен.");
                return;
            }
            boolean requested = choice == 0;
            boolean changed = AmbientLighting.setEnabled(this, requested);
            Toast.makeText(this, changed ? (requested ? "Системный режим подсветки включён" : "Системный режим подсветки выключен")
                : "Телевизор не подтвердил изменение подсветки", Toast.LENGTH_LONG).show();
        });
    }

    private void showSpeedMenu() {
        final float[] speeds = {0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] names = {"0,75×", "1× (рекомендуется для оптики)", "1,25×", "1,5×", "2×"};
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(player.getPlaybackParameters().speed - speeds[i]) < 0.01f) names[i] += "  ✓";
        }
        showChoiceDialog("Скорость", java.util.Arrays.asList(names), index -> player.setPlaybackSpeed(speeds[index]));
    }

    private void showResizeMenu() {
        String[] choices = {"По размеру — без обрезки", "Заполнить экран — с обрезкой",
            "Растянуть — пропорции меняются", "Как работает масштабирование"};
        showChoiceDialog("Масштаб изображения", java.util.Arrays.asList(choices), index -> {
                if (index < resizeModes.length) {
                    playerView.setResizeMode(resizeModes[index]);
                    Toast.makeText(this, choices[index], Toast.LENGTH_SHORT).show();
                } else {
                    showInfoDialog("720p на 4K-телевизоре",
                        "Видео автоматически масштабируется до размера экрана ТВ. "
                            + "Исходный файл 720p остаётся 720p по детализации; выбор 1080p или 1440p "
                            + "в качестве промежуточного размера не добавит деталей. "
                            + "Чёткость и шумоподавление можно настроить в меню изображения телевизора. "
                            + "Режим «По размеру» сохраняет пропорции и не обрезает кадр.");
                }
            });
    }

    private void showDecoderInfo() {
        StringBuilder formats = new StringBuilder();
        formats.append("Системные видеодекодеры · 3840×2160 / 60 fps:\n");
        for (String[] video : new String[][] {
            {"AVC / H.264", "video/avc"},
            {"HEVC / H.265", "video/hevc"},
            {"VP9", "video/x-vnd.on2.vp9"},
            {"AV1", "video/av01"},
            {"Dolby Vision", "video/dolby-vision"}
        }) {
            CodecSupport support = inspectVideoCodec(video[1]);
            formats.append(video[0]).append(": ");
            if (!support.found) formats.append("не обнаружен");
            else if (support.hardware4k60) formats.append("аппаратный 4K/60 заявлен");
            else if (support.hardware) formats.append("аппаратный есть, 4K/60 не заявлен");
            else if (support.sizeAndRate4k60) formats.append("4K/60 заявлен программным декодером");
            else formats.append("есть, 4K/60 не заявлен");
            formats.append('\n');
        }
        formats.append("\nСистемные аудиодекодеры:\n");
        for (String[] audio : new String[][] {
            {"AAC", "audio/mp4a-latm"},
            {"AC-3", "audio/ac3"},
            {"E-AC-3", "audio/eac3"},
            {"DTS", "audio/vnd.dts"},
            {"FLAC", "audio/flac"},
            {"Opus", "audio/opus"},
            {"TrueHD", "audio/true-hd"}
        }) {
            formats.append(audio[0]).append(": ")
                .append(hasSystemDecoder(audio[1]) ? "есть" : "не обнаружен").append('\n');
        }
        formats.append("\nЭто возможности, заявленные прошивкой, а не гарантия плавности каждого файла или профиля HDR.");
        formats.append("\nФактически запущенный видеодекодер: ")
            .append(currentVideoDecoder == null ? "ещё не запущен" : currentVideoDecoder + decoderAcceleration(currentVideoDecoder));
        formats.append("\nФактически запущенный аудиодекодер: ")
            .append(currentAudioDecoder == null ? "ещё не запущен" : currentAudioDecoder);
        formats.append("\nВидео: SurfaceView. Звук: системный аудиовыход; наличие декодера не означает передачу формата через оптику.");
        if (player != null && player.getVideoFormat() != null) {
            Format f = player.getVideoFormat();
            formats.append("\n\nТекущее видео: ").append(f.width).append("×").append(f.height);
            if (f.frameRate > 0) formats.append(String.format(Locale.ROOT, " · %.2f fps", f.frameRate));
            formats.append(" · ").append(f.sampleMimeType == null ? "неизвестный формат" : f.sampleMimeType);
            formats.append(" · ").append(dynamicRange(f));
            if (f.codecs != null) formats.append(" · ").append(f.codecs);
        }
        if (player != null && player.getAudioFormat() != null) {
            Format f = player.getAudioFormat();
            formats.append("\nТекущий звук: ").append(f.sampleMimeType == null ? "неизвестный формат" : f.sampleMimeType);
            if (f.channelCount > 0) formats.append(" · ").append(f.channelCount).append(" каналов");
        }
        showInfoDialog("Устройство и поток", formats.toString());
    }

    private static String decoderAcceleration(String decoderName) {
        if (Build.VERSION.SDK_INT < 29) return " · тип не сообщается Android 9";
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (decoderName.equals(codec.getName()))
                    return codec.isHardwareAccelerated() ? " · аппаратный по данным прошивки"
                        : " · не аппаратный по данным прошивки";
            }
        } catch (RuntimeException ignored) { }
        return " · тип не определён";
    }

    private static CodecSupport inspectVideoCodec(String mime) {
        CodecSupport result = new CodecSupport();
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!mime.equalsIgnoreCase(type)) continue;
                    result.found = true;
                    boolean hardware = Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated();
                    result.hardware |= hardware;
                    try {
                        MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(type).getVideoCapabilities();
                        if (video != null && video.areSizeAndRateSupported(3840, 2160, 60)) {
                            result.sizeAndRate4k60 = true;
                            result.hardware4k60 |= hardware;
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
        } catch (RuntimeException ignored) { }
        return result;
    }

    private static boolean hasSystemDecoder(String mime) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) return true;
                }
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static final class CodecSupport {
        boolean found;
        boolean hardware;
        boolean sizeAndRate4k60;
        boolean hardware4k60;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (player != null && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (controls.getVisibility() == View.VISIBLE) controls.setVisibility(View.GONE);
                else finish();
            }
            return true;
        }
        if (player != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (controls.getVisibility() == View.VISIBLE) scheduleHide();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else if (lastError != null) retryPlayback();
                else if (player.isPlaying()) player.pause(); else player.play();
                showControls(); return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND) { seekRelative(-10_000); return true; }
            if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { seekRelative(30_000); return true; }
            if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_CHANNEL_UP) {
                nextEpisode(); showControls(); return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS || key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                previousEpisode(); showControls(); return true;
            }
            if (controls.getVisibility() != View.VISIBLE && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                showControls(); playButton.requestFocus(); return true;
            }
            if (controls.getVisibility() != View.VISIBLE && key == KeyEvent.KEYCODE_DPAD_LEFT) { seekRelative(-10_000); return true; }
            if (controls.getVisibility() != View.VISIBLE && key == KeyEvent.KEYCODE_DPAD_RIGHT) { seekRelative(30_000); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hide);
        savePosition();
        if (player != null) {
            startIndex = Math.max(0, player.getCurrentMediaItemIndex());
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }
}
