package com.aurora.tvplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 12;
    private static final int RECOGNIZE_VOICE = 13;
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final List<VideoLibrary.Entry> videos = new ArrayList<>();
    private ListView list;
    private TextView emptyMessage;
    private VideoAdapter adapter;
    private TextView sectionTitle;
    private Button recentButton;
    private Button lastFolderButton;
    private LinearLayout quickRow;
    private Button deviceButton;
    private View firstActionButton;
    private SharedPreferences prefs;
    private String loadedFolder;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("library", MODE_PRIVATE);
        buildUi();
        checkDevice();
        firstActionButton.post(firstActionButton::requestFocus);
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
            launchSingle(getIntent().getData(), VideoLibrary.displayName(this, getIntent().getData()));
        }
        // Do not crawl a removable disk when the home screen starts.
        loadedFolder = prefs.getString("last_folder_path", prefs.getString("folder_path", null));
    }

    @Override protected void onResume() {
        super.onResume();
        String savedFolder = prefs.getString("last_folder_path", prefs.getString("folder_path", null));
        if (savedFolder != null && !savedFolder.equals(loadedFolder)) scanSavedFolder();
        if (recentButton != null) {
            String recent = prefs.getString("recent_uri", null);
            recentButton.setVisibility(recent == null ? View.GONE : View.VISIBLE);
            recentButton.setText("▶  Продолжить: " + prefs.getString("recent_title", "видео"));
        }
        if (lastFolderButton != null) {
            String path = prefs.getString("last_folder_path", null);
            lastFolderButton.setVisibility(path == null ? View.GONE : View.VISIBLE);
            if (path != null) lastFolderButton.setText("▣  Последняя папка: " + new File(path).getName());
        }
        if (quickRow != null) quickRow.setVisibility(
            recentButton.getVisibility() == View.GONE && lastFolderButton.getVisibility() == View.GONE
                ? View.GONE : View.VISIBLE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG);
        setContentView(root);
        ScrollView headerScroll = new ScrollView(this);
        headerScroll.setFillViewport(false);
        headerScroll.setVerticalScrollBarEnabled(false);
        root.addView(headerScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(TvUi.dp(this, 48), TvUi.dp(this, 14), TvUi.dp(this, 48), TvUi.dp(this, 8));
        headerScroll.addView(header);
        list = new ListView(this);
        list.setBackgroundColor(TvUi.BG);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setFocusable(true);
        list.setPadding(TvUi.dp(this, 48), 0, TvUi.dp(this, 48), TvUi.dp(this, 20));
        list.setClipToPadding(false);

        TextView eyebrow = TvUi.text(this, "A U R O R A   T V                                      ВАШ КИНОЗАЛ", 13, TvUi.ACCENT);
        header.addView(eyebrow);
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroShape = TvUi.shape(TvUi.PANEL, TvUi.dp(this, 18));
        hero.setBackground(heroShape);
        hero.setClipToOutline(true);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 96));
        heroParams.topMargin = TvUi.dp(this, 7);
        header.addView(hero, heroParams);
        ImageView banner = new ImageView(this);
        banner.setImageResource(R.drawable.aurora_tv_banner);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.setContentDescription("Aurora TV");
        hero.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        TextView heading = TvUi.text(this, "Выберите источник", 22, TvUi.TEXT);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = TvUi.dp(this, 9);
        header.addView(heading, headingParams);
        TextView hint = TvUi.text(this, "USB, файлы и сетевые ссылки", 14, TvUi.MUTED);
        header.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 88));
        actionsParams.topMargin = TvUi.dp(this, 10);
        header.addView(actions, actionsParams);
        firstActionButton = addAction(actions, "▣", "USB / папки", "Встроенный проводник", () -> startActivity(new Intent(this, BrowserActivity.class)));
        addAction(actions, "⌂", "Домашняя сеть", "Папки ПК и NAS · SMB", () -> startActivity(new Intent(this, NetworkBrowserActivity.class)));
        addAction(actions, "↗", "Ссылка", "HTTP · HLS · DASH", this::askUrl);
        addAction(actions, "●", "Голос", "RU · УКР · EN", this::chooseVoiceLanguage);

        recentButton = TvUi.button(this, "▶  Продолжить просмотр");
        lastFolderButton = TvUi.button(this, "▣  Последняя папка");
        quickRow = new LinearLayout(this);
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 50));
        quickParams.topMargin = TvUi.dp(this, 7);
        header.addView(quickRow, quickParams);
        LinearLayout.LayoutParams quickItem = new LinearLayout.LayoutParams(0, -1, 1);
        quickItem.rightMargin = TvUi.dp(this, 8);
        quickRow.addView(recentButton, quickItem);
        quickRow.addView(lastFolderButton, new LinearLayout.LayoutParams(0, -1, 1));
        recentButton.setSingleLine(true);
        recentButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lastFolderButton.setSingleLine(true);
        lastFolderButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        recentButton.setOnClickListener(v -> {
            String uri = prefs.getString("recent_uri", null);
            if (uri == null) return;
            if (uri.startsWith("smb://") && !SmbConnection.canOpen(uri)) {
                Toast.makeText(this, "Сначала подключитесь к сетевой папке", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, NetworkBrowserActivity.class));
            } else launchSingle(Uri.parse(uri), prefs.getString("recent_title", "Видео"));
        });
        lastFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, BrowserActivity.class);
            intent.putExtra("initial_folder", prefs.getString("last_folder_path", null));
            startActivity(intent);
        });

        LinearLayout secondary = new LinearLayout(this);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42));
        secondaryParams.topMargin = TvUi.dp(this, 6);
        header.addView(secondary, secondaryParams);
        Button fileButton = TvUi.button(this, "▶  Открыть файл");
        secondary.addView(fileButton, new LinearLayout.LayoutParams(0, -1, 1));
        fileButton.setOnClickListener(v -> openFilePicker());
        Button audioButton = TvUi.button(this, "ⓘ  Звук, Dolby Vision и форматы");
        LinearLayout.LayoutParams audioParams = new LinearLayout.LayoutParams(0, -1, 1);
        audioParams.leftMargin = TvUi.dp(this, 8);
        secondary.addView(audioButton, audioParams);
        audioButton.setOnClickListener(v -> showAudioHelp());

        deviceButton = TvUi.button(this, "◉  Мой телевизор · проверка возможностей");
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(0, -1, 1);
        deviceParams.leftMargin = TvUi.dp(this, 8);
        secondary.addView(deviceButton, deviceParams);
        deviceButton.setOnClickListener(v -> showDeviceReport());

        sectionTitle = TvUi.text(this, "ВИДЕО В ПОСЛЕДНЕЙ ПАПКЕ", 18, TvUi.TEXT);
        sectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        sectionTitle.setPadding(0, TvUi.dp(this, 12), 0, TvUi.dp(this, 7));
        header.addView(sectionTitle);
        emptyMessage = TvUi.text(this, "", 17, TvUi.MUTED);
        emptyMessage.setPadding(TvUi.dp(this, 20), TvUi.dp(this, 10), TvUi.dp(this, 20), TvUi.dp(this, 10));
        emptyMessage.setBackground(TvUi.shape(TvUi.PANEL, TvUi.dp(this, 14)));
        header.addView(emptyMessage);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new VideoAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < videos.size()) launchPlaylist(position);
        });
        showEmpty("Откройте «USB / папки» и выберите папку с фильмами.");
    }

    private View addAction(LinearLayout row, String icon, String title, String subtitle, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(TvUi.dp(this, 18), TvUi.dp(this, 8), TvUi.dp(this, 12), TvUi.dp(this, 8));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff173746, 0xff112433});
        normal.setCornerRadius(TvUi.dp(this, 14));
        normal.setStroke(TvUi.dp(this, 1), 0xff31505c);
        card.setBackground(normal);
        TextView symbol = TvUi.text(this, icon, 25, TvUi.ACCENT);
        TextView name = TvUi.text(this, title, 18, TvUi.TEXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView detail = TvUi.text(this, subtitle, 12, TvUi.MUTED);
        card.addView(symbol);
        card.addView(name);
        card.addView(detail);
        card.setOnFocusChangeListener((v, focused) -> {
            card.setBackground(focused ? TvUi.shape(TvUi.ACCENT, TvUi.dp(this, 14)) : normal);
            symbol.setTextColor(focused ? TvUi.BG : TvUi.ACCENT);
            name.setTextColor(focused ? TvUi.BG : TvUi.TEXT);
            detail.setTextColor(focused ? TvUi.BG : TvUi.MUTED);
            card.setScaleX(focused ? 1.025f : 1f);
            card.setScaleY(focused ? 1.025f : 1f);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.rightMargin = TvUi.dp(this, 10);
        row.addView(card, lp);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private void showEmpty(String message) {
        emptyMessage.setText(message);
        emptyMessage.setVisibility(View.VISIBLE);
    }

    private void scanSavedFolder() {
        String path = prefs.getString("last_folder_path", prefs.getString("folder_path", null));
        if (path != null) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager(new File(path))) {
                showEmpty("Для накопителя нужен доступ к файлам. Откройте «USB / папки» и разрешите доступ.");
                return;
            }
            loadedFolder = path;
            videos.clear();
            adapter.notifyDataSetChanged();
            showEmpty("Открываю последнюю папку…");
            scanner.execute(() -> {
                try {
                    List<VideoLibrary.Entry> found = VideoLibrary.scanDirectFile(new File(path));
                    runOnUiThread(() -> showVideos(found));
                } catch (Exception e) {
                    runOnUiThread(() -> showEmpty("Не удалось прочитать накопитель. Проверьте доступ к USB."));
                }
            });
        }
    }

    private void showVideos(List<VideoLibrary.Entry> found) {
        videos.clear();
        videos.addAll(found);
        sectionTitle.setText("ВИДЕО В ПОСЛЕДНЕЙ ПАПКЕ  ·  " + videos.size());
        emptyMessage.setVisibility(videos.isEmpty() ? View.VISIBLE : View.GONE);
        if (videos.isEmpty()) showEmpty("В выбранной папке не нашлось видео.");
        adapter.notifyDataSetChanged();
    }

    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return videos.size(); }
        @Override public Object getItem(int position) { return videos.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row;
            if (convertView instanceof TextView) row = (TextView) convertView;
            else {
                row = TvUi.text(MainActivity.this, "", 17, TvUi.TEXT);
                int radius = TvUi.dp(MainActivity.this, 10);
                StateListDrawable background = new StateListDrawable();
                background.addState(new int[]{android.R.attr.state_selected}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{android.R.attr.state_focused}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{}, TvUi.shape(TvUi.PANEL, radius));
                row.setBackground(background);
                row.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_focused}, {}},
                    new int[]{TvUi.BG, TvUi.BG, TvUi.TEXT}));
                row.setPadding(TvUi.dp(MainActivity.this, 22), 0, TvUi.dp(MainActivity.this, 22), 0);
                row.setMinHeight(TvUi.dp(MainActivity.this, 56));
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            row.setText("▶    " + videos.get(position).name);
            return row;
        }
    }

    private void askUrl() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com/video.m3u8");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(TvUi.dp(this, 22), TvUi.dp(this, 16), TvUi.dp(this, 22), TvUi.dp(this, 16));
        new AlertDialog.Builder(this).setTitle("Открыть сетевое видео")
            .setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть", (dialog, which) -> {
                String value = input.getText().toString().trim();
                Uri uri = Uri.parse(value);
                if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null) {
                    launchSingle(uri, VideoLibrary.displayName(this, uri));
                } else Toast.makeText(this, "Введите ссылку http или https", Toast.LENGTH_LONG).show();
            }).show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "application/octet-stream", "application/x-matroska"});
        try { startActivityForResult(intent, PICK_VIDEO); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, "На ТВ нет системного выбора файлов. Откройте «USB / папки».", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseVoiceLanguage() {
        String[] labels = {"Русский", "Українська", "English"};
        String[] languages = {"ru-RU", "uk-UA", "en-US"};
        new AlertDialog.Builder(this).setTitle("Голосовая команда · язык")
            .setItems(labels, (dialog, index) -> startVoiceRecognition(languages[index]))
            .setNegativeButton("Отмена", null).show();
    }

    private void startVoiceRecognition(String language) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Например: открой сеть или найди фильм");
        try { startActivityForResult(intent, RECOGNIZE_VOICE); }
        catch (ActivityNotFoundException | SecurityException e) {
            new AlertDialog.Builder(this).setTitle("Распознавание недоступно")
                .setMessage("На телевизоре нет доступной службы распознавания речи для приложений. "
                    + "Проверьте установку и настройки голосовых служб Google TV; "
                    + "управление кнопками пульта остаётся доступным.")
                .setPositiveButton("Понятно", null).show();
        }
    }

    private void handleVoiceCommand(String spoken) {
        String command = spoken.toLowerCase(Locale.ROOT).trim();
        if (command.contains("сеть") || command.contains("сетев") || command.contains("мереж")
                || command.contains("nas") || command.contains("network") || command.contains("компьютер")) {
            startActivity(new Intent(this, NetworkBrowserActivity.class)); return;
        }
        if (command.contains("провод") || command.contains("провід") || command.contains("папк")
                || command.contains("folder") || command.contains("browse") || command.equals("files")) {
            startActivity(new Intent(this, BrowserActivity.class)); return;
        }
        if (command.contains("ссыл") || command.contains("посилан") || command.contains("link") || command.contains("url")) {
            askUrl(); return;
        }
        if (command.contains("продолж") || command.contains("продовж") || command.contains("resume") || command.contains("continue")) {
            String recent = prefs.getString("recent_uri", null);
            if (recent != null) launchSingle(Uri.parse(recent), prefs.getString("recent_title", "Видео"));
            else Toast.makeText(this, "История просмотра пока пуста", Toast.LENGTH_LONG).show();
            return;
        }
        if (command.contains("звук") || command.contains("аудіо") || command.contains("sound")
                || command.contains("audio") || command.contains("dolby")) {
            showAudioHelp(); return;
        }
        if (command.equals("открой видео") || command.equals("відкрий відео")
                || command.equals("open video") || command.equals("open file")) {
            openFilePicker(); return;
        }
        String query = command.replaceFirst("^(найди|найти|знайди|знайти|включи|увімкни|покажи|find|play|show)\\s+", "").trim();
        for (int i = 0; i < videos.size(); i++) {
            if (!query.isEmpty() && videos.get(i).name.toLowerCase(Locale.ROOT).contains(query)) {
                launchPlaylist(i); return;
            }
        }
        Toast.makeText(this, "Не нашлось: «" + spoken + "». Попробуйте назвать файл или команду.", Toast.LENGTH_LONG).show();
    }

    private void showAudioHelp() {
        new AlertDialog.Builder(this).setTitle("HDR и звук через оптику")
            .setMessage("HDR10, HDR10+, HLG и Dolby Vision включаются системой автоматически для совместимого файла и экрана. Если Xbox показывает Dolby Vision, телевизор принимает его по HDMI; для DV-файлов в приложении дополнительно нужен подходящий системный декодер и профиль. В меню плеера «Качество видео / HDR» можно выбрать SDR-дорожку, если она есть в потоке. Улучшайзеры изображения ТВ приложение не переключает.\n\nDolby Digital, DTS и Atmos зависят от дорожки и всей аудиоцепочки. Для TCL S643W выберите на саундбаре Optical, а на ТВ — оптический аудиовыход и цифровой формат Auto или Pass-through. Если дорожка молчит, установите PCM в настройках ТВ или выберите другую дорожку в плеере. Приложение не может менять системный режим оптического выхода. Оптика не передаёт Dolby TrueHD/Atmos и многоканальный PCM. Скорость 1× лучше всего сохраняет сквозную передачу звука.")
            .setPositiveButton("Понятно", null).show();
    }

    private void checkDevice() {
        if (DeviceProfile.cached(this) != null) return;
        deviceButton.setText("◉  Проверяю возможности телевизора…");
        scanner.execute(() -> {
            DeviceProfile.refresh(getApplicationContext());
            runOnUiThread(() -> {
                if (!isFinishing() && deviceButton != null)
                    deviceButton.setText("◉  Мой телевизор · проверка завершена");
            });
        });
    }

    private void showDeviceReport() {
        String report = DeviceProfile.cached(this);
        if (report == null) {
            Toast.makeText(this, "Проверка ещё выполняется", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Возможности этого ТВ")
            .setMessage(report)
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Проверить снова", (dialog, which) -> {
                deviceButton.setText("◉  Проверяю возможности телевизора…");
                scanner.execute(() -> {
                    DeviceProfile.refresh(getApplicationContext());
                    runOnUiThread(() -> {
                        if (!isFinishing() && deviceButton != null) {
                            deviceButton.setText("◉  Мой телевизор · проверка завершена");
                            showDeviceReport();
                        }
                    });
                });
            }).show();
    }

    private void launchSingle(Uri uri, String title) {
        ArrayList<String> uris = new ArrayList<>(); uris.add(uri.toString());
        ArrayList<String> titles = new ArrayList<>(); titles.add(title);
        launch(uris, titles, 0);
    }

    private void launchPlaylist(int index) {
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        for (VideoLibrary.Entry entry : videos) { uris.add(entry.uri.toString()); titles.add(entry.name); }
        launch(uris, titles, index);
    }

    private void launch(ArrayList<String> uris, ArrayList<String> titles, int index) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putStringArrayListExtra("uris", uris);
        intent.putStringArrayListExtra("titles", titles);
        intent.putExtra("index", index);
        startActivity(intent);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == RECOGNIZE_VOICE) {
            if (result == RESULT_OK && data != null) {
                ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (heard != null && !heard.isEmpty()) handleVoiceCommand(heard.get(0));
            }
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_VIDEO) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            launchSingle(uri, VideoLibrary.displayName(this, uri));
        }
    }

    @Override protected void onDestroy() {
        scanner.shutdownNow();
        super.onDestroy();
    }
}
