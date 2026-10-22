package com.aurora.tvplayer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote-friendly direct browser for Android TV storage volumes. */
public final class BrowserActivity extends Activity {
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final ArrayList<Entry> visible = new ArrayList<>();
    private File rootFolder, currentFolder, pendingVolume;
    private File pendingInitialFolder;
    private int scanId;
    private boolean showAllFiles;
    private boolean accessSettingsOpened;
    private String query = "";
    private TextView title, status;
    private Button volumesButton, filterButton;
    private ListView list;
    private BaseAdapter adapter;

    private static final class Entry {
        final File file;
        final String name;
        final boolean directory, video, volume;
        Entry(File file, String name, boolean volume) {
            this.file = file;
            this.name = name;
            this.volume = volume;
            directory = volume || file.isDirectory();
            video = !directory && VideoLibrary.isVideoName(name);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        showVolumes();
        String initial = getIntent().getStringExtra("initial_folder");
        if (initial == null || !restoreFolder(initial)) volumesButton.post(volumesButton::requestFocus);
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingVolume != null && hasAccess(pendingVolume)) {
            File selected = pendingVolume;
            pendingVolume = null;
            accessSettingsOpened = false;
            openVolume(selected);
        } else if (pendingVolume != null && accessSettingsOpened) {
            accessSettingsOpened = false;
            status.setText("Доступ к USB ещё не включён. В настройках Aurora TV разрешите управление файлами.");
            new AlertDialog.Builder(this).setTitle("Разрешение не включено")
                .setMessage("Если телевизор не показал переключатель доступа ко всем файлам, откройте страницу Aurora TV и выберите «Разрешения» или «Специальный доступ».")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Страница Aurora TV", (dialog, which) -> openAppDetails()).show();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG);
        root.setPadding(TvUi.dp(this, 42), TvUi.dp(this, 16), TvUi.dp(this, 42), TvUi.dp(this, 14));
        setContentView(root);
        root.addView(TvUi.text(this, "AURORA TV  /  ФАЙЛЫ", 13, TvUi.ACCENT));
        title = TvUi.text(this, "Накопители", 23, TvUi.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        root.addView(title);

        LinearLayout toolbar = new LinearLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42));
        tp.topMargin = TvUi.dp(this, 10);
        root.addView(toolbar, tp);
        volumesButton = action(toolbar, "Диски", this::showVolumes);
        action(toolbar, "↑ Назад", this::goUp);
        filterButton = action(toolbar, "Все файлы", () -> {
            showAllFiles = !showAllFiles;
            filterButton.setText(showAllFiles ? "Только видео" : "Все файлы");
            applyFilter();
        });
        action(toolbar, "Поиск", this::askSearch);

        status = TvUi.text(this, "", 15, TvUi.MUTED);
        status.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 8));
        root.addView(status);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(TvUi.BG));
        list.setDividerHeight(TvUi.dp(this, 3));
        list.setBackgroundColor(TvUi.BG);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            Entry selected = visible.get(position);
            if (selected.volume) openVolume(selected.file);
            else if (selected.directory) openFolder(selected.file);
            else if (selected.video) play(selected);
            else Toast.makeText(this, "Это не видеофайл", Toast.LENGTH_SHORT).show();
        });
    }

    private Button action(LinearLayout row, String label, Runnable click) {
        Button button = TvUi.button(this, label);
        button.setTextSize(14);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setPadding(TvUi.dp(this, 5), 0, TvUi.dp(this, 5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.rightMargin = TvUi.dp(this, 8);
        row.addView(button, lp);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private void showVolumes() {
        ++scanId;
        rootFolder = currentFolder = null;
        query = "";
        title.setText("Накопители");
        entries.clear();
        try {
            StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= 30) {
                for (StorageVolume volume : manager.getStorageVolumes()) {
                    File directory = volume.getDirectory();
                    if (directory == null) continue;
                    String name = volume.getDescription(this);
                    if (name == null || name.trim().isEmpty())
                        name = volume.isPrimary() ? "Память ТВ" : "USB / внешний диск";
                    entries.add(new Entry(directory, name, true));
                }
            } else if (Build.VERSION.SDK_INT >= 24) {
                File[] appDirs = getExternalFilesDirs(null);
                for (int i = 0; i < appDirs.length; i++) {
                    File directory = legacyVolumeRoot(appDirs[i]);
                    if (directory == null) continue;
                    boolean duplicate = false;
                    for (Entry existing : entries) if (existing.file.equals(directory)) duplicate = true;
                    if (!duplicate) entries.add(new Entry(directory,
                        i == 0 ? "Память ТВ" : "USB / внешний диск", true));
                }
            }
        } catch (RuntimeException ignored) { }
        applyFilter();
        status.setText(entries.isEmpty() ? "ТВ не сообщил о накопителях. Проверьте подключение USB."
            : "Выберите диск, затем откройте нужную папку.");
    }

    private boolean hasAccess(File directory) {
        if (Build.VERSION.SDK_INT < 30) return Build.VERSION.SDK_INT < 23
            || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return Environment.isExternalStorageManager(directory);
    }

    private File legacyVolumeRoot(File appDir) {
        if (appDir == null) return null;
        File cursor = appDir;
        while (cursor != null && !"Android".equals(cursor.getName())) cursor = cursor.getParentFile();
        return cursor == null ? null : cursor.getParentFile();
    }

    private void openVolume(File directory) {
        if (!hasAccess(directory)) { pendingVolume = directory; requestAccess(); return; }
        rootFolder = directory;
        getSharedPreferences("library", MODE_PRIVATE).edit().putString("folder_path", directory.getAbsolutePath()).apply();
        File target = pendingInitialFolder;
        pendingInitialFolder = null;
        openFolder(target != null && target.isDirectory() ? target : directory);
    }

    private boolean restoreFolder(String path) {
        try {
            File target = new File(path).getCanonicalFile();
            for (Entry volume : entries) {
                File root = volume.file.getCanonicalFile();
                String base = root.getPath() + File.separator;
                if (volume.volume && (target.equals(root) || target.getPath().startsWith(base))) {
                    pendingInitialFolder = target;
                    openVolume(volume.file);
                    return true;
                }
            }
        } catch (java.io.IOException | SecurityException ignored) { }
        return false;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 41);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Доступ к USB и файлам")
            .setMessage("Для встроенного проводника Android 11 требует разрешение «Доступ ко всем файлам». "
                + "Разрешите его для Aurora TV в настройках и вернитесь сюда. Плеер не изменяет и не удаляет файлы.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть настройки", (dialog, which) -> openAccessSettings()).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 41 && pendingVolume != null) {
            if (hasAccess(pendingVolume)) {
                File selected = pendingVolume;
                pendingVolume = null;
                openVolume(selected);
            } else status.setText("Доступ к файлам не разрешён. Выберите диск и разрешите доступ.");
        }
    }

    private void openAccessSettings() {
        try {
            accessSettingsOpened = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException first) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            accessSettingsOpened = true;
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            accessSettingsOpened = false;
            status.setText("ТВ не открыл настройки Aurora TV. Найдите приложение в настройках вручную.");
        }
    }

    private void openFolder(File folder) {
        if (rootFolder == null) return;
        query = "";
        currentFolder = folder;
        getSharedPreferences("library", MODE_PRIVATE).edit()
            .putString("last_folder_path", folder.getAbsolutePath()).apply();
        title.setText(folder.equals(rootFolder) ? "Папки на диске" : folder.getName());
        loadCurrentFolder();
    }

    private void loadCurrentFolder() {
        if (currentFolder == null) { showVolumes(); return; }
        File folder = currentFolder;
        int request = ++scanId;
        entries.clear();
        visible.clear();
        adapter.notifyDataSetChanged();
        status.setText("Читаю папку…");
        scanner.execute(() -> {
            File[] files;
            try { files = folder.listFiles(); }
            catch (SecurityException e) { files = null; }
            if (files != null) Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            final File[] found = files;
            runOnUiThread(() -> {
                if (request != scanId || isFinishing()) return;
                if (found == null) {
                    status.setText("Нет доступа к папке. Разрешите доступ к USB или проверьте формат диска.");
                    return;
                }
                for (File file : found) entries.add(new Entry(file, file.getName(), false));
                applyFilter();
                if (!visible.isEmpty()) {
                    list.setSelection(0);
                    list.requestFocus();
                }
            });
        });
    }

    private static boolean isSystemFolder(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith(".") || lower.startsWith("$") || lower.startsWith("found.")
            || lower.equals("android") || lower.equals("lost.dir")
            || lower.equals("system volume information");
    }

    private void applyFilter() {
        visible.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (Entry entry : entries) {
            if ((entry.volume || entry.video || (entry.directory && (!isSystemFolder(entry.name) || showAllFiles))
                    || (showAllFiles && !entry.directory))
                && entry.name.toLowerCase(Locale.ROOT).contains(needle)) visible.add(entry);
        }
        adapter.notifyDataSetChanged();
        if (currentFolder != null) status.setText(visible.isEmpty() ? "В этой папке нет видео или подпапок"
            : "Папок и файлов: " + visible.size());
    }

    private void goUp() {
        if (currentFolder == null || currentFolder.equals(rootFolder)) { showVolumes(); return; }
        File parent = currentFolder.getParentFile();
        if (parent == null) showVolumes(); else openFolder(parent);
    }

    private void askSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(query);
        input.setHint("Название файла или папки");
        new AlertDialog.Builder(this).setTitle("Поиск в текущей папке")
            .setView(input).setNegativeButton("Сбросить", (dialog, which) -> { query = ""; applyFilter(); })
            .setPositiveButton("Найти", (dialog, which) -> { query = input.getText().toString().trim(); applyFilter(); }).show();
    }

    private void play(Entry selected) {
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        int index = -1;
        for (Entry entry : entries) {
            if (!entry.video || uris.size() >= 500) continue;
            if (entry == selected) index = uris.size();
            uris.add(Uri.fromFile(entry.file).toString());
            titles.add(entry.name);
        }
        if (index < 0) {
            uris.clear();
            titles.clear();
            uris.add(Uri.fromFile(selected.file).toString());
            titles.add(selected.name);
            index = 0;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putStringArrayListExtra("uris", uris);
        intent.putStringArrayListExtra("titles", titles);
        intent.putExtra("index", index);
        startActivity(intent);
    }

    @Override public void onBackPressed() {
        if (currentFolder != null) goUp(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        ++scanId;
        scanner.shutdownNow();
        super.onDestroy();
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row;
            if (convertView instanceof TextView) row = (TextView) convertView;
            else {
                row = TvUi.text(BrowserActivity.this, "", 18, TvUi.TEXT);
                int radius = TvUi.dp(BrowserActivity.this, 6);
                StateListDrawable background = new StateListDrawable();
                background.addState(new int[]{android.R.attr.state_selected}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{android.R.attr.state_focused}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{}, TvUi.shape(0xff142431, radius));
                row.setBackground(background);
                row.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_focused}, {}},
                    new int[]{TvUi.BG, TvUi.BG, TvUi.TEXT}));
                row.setPadding(TvUi.dp(BrowserActivity.this, 16), 0, TvUi.dp(BrowserActivity.this, 16), 0);
                row.setMinHeight(TvUi.dp(BrowserActivity.this, 47));
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            Entry entry = visible.get(position);
            row.setText((entry.volume ? "▣   " : entry.directory ? "▸   " : entry.video ? "▶   " : "·   ") + entry.name);
            row.setAlpha(entry.directory || entry.video ? 1f : 0.6f);
            return row;
        }
    }
}
