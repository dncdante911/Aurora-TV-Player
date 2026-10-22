package com.aurora.tvplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only SMB folder browser. Local USB navigation is intentionally untouched. */
public final class NetworkBrowserActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<SmbConnection.Entry> entries = new ArrayList<>();
    private final ArrayList<SmbConnection.Entry> visible = new ArrayList<>();
    private int requestId;
    private String folderUrl, query = "";
    private LinearLayout connectionPanel;
    private EditText hostInput, shareInput, userInput, passwordInput;
    private TextView title, status;
    private ListView list;
    private BaseAdapter adapter;
    private Button connectButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        SmbConnection session = SmbConnection.active();
        if (session != null) {
            connectionPanel.setVisibility(View.GONE);
            openFolder(session.rootUrl, "Сетевая папка");
        } else {
            status.setText("Укажите адрес сервера и имя общей папки. Компьютер и ТВ должны быть в одной сети.");
            hostInput.requestFocus();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(TvUi.dp(this, 42), TvUi.dp(this, 15), TvUi.dp(this, 42), TvUi.dp(this, 14));
        root.setBackgroundColor(TvUi.BG);
        setContentView(root);
        root.addView(TvUi.text(this, "AURORA TV  /  ДОМАШНЯЯ СЕТЬ", 13, TvUi.ACCENT));
        title = TvUi.text(this, "Сетевые папки · SMB", 23, TvUi.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        root.addView(title);

        LinearLayout toolbar = new LinearLayout(this);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42));
        toolbarParams.topMargin = TvUi.dp(this, 9);
        root.addView(toolbar, toolbarParams);
        action(toolbar, "↑ Назад", this::goUp);
        action(toolbar, "Подключение", () -> {
            connectionPanel.setVisibility(connectionPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (connectionPanel.getVisibility() == View.VISIBLE) hostInput.requestFocus();
        });
        action(toolbar, "Обновить", () -> { if (folderUrl != null) openFolder(folderUrl, title.getText().toString()); });
        action(toolbar, "Поиск", this::askSearch);

        connectionPanel = new LinearLayout(this);
        connectionPanel.setOrientation(LinearLayout.HORIZONTAL);
        connectionPanel.setPadding(TvUi.dp(this, 12), TvUi.dp(this, 7), TvUi.dp(this, 12), TvUi.dp(this, 7));
        connectionPanel.setBackground(TvUi.shape(TvUi.PANEL, TvUi.dp(this, 10)));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 65));
        panelParams.topMargin = TvUi.dp(this, 8);
        root.addView(connectionPanel, panelParams);
        hostInput = field("Адрес ПК / NAS", getSharedPreferences("network", MODE_PRIVATE).getString("host", ""), 3);
        shareInput = field("Общая папка", getSharedPreferences("network", MODE_PRIVATE).getString("share", ""), 2);
        userInput = field("Пользователь", getSharedPreferences("network", MODE_PRIVATE).getString("user", ""), 2);
        passwordInput = field("Пароль", "", 2);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connectButton = TvUi.button(this, "Подключить");
        connectButton.setTextSize(14);
        connectionPanel.addView(connectButton, new LinearLayout.LayoutParams(0, -1, 2));
        connectButton.setOnClickListener(v -> connect());

        status = TvUi.text(this, "", 15, TvUi.MUTED);
        status.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 8));
        root.addView(status);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(TvUi.BG));
        list.setDividerHeight(TvUi.dp(this, 3));
        list.setBackgroundColor(TvUi.BG);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        adapter = new NetworkAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visible.size()) return;
            SmbConnection.Entry entry = visible.get(position);
            if (entry.directory) openFolder(entry.url, entry.name);
            else if (entry.video) play(entry);
            else Toast.makeText(this, "Это не видеофайл", Toast.LENGTH_SHORT).show();
        });
    }

    private EditText field(String hint, String initial, int weight) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setHint(hint);
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.rightMargin = TvUi.dp(this, 8);
        connectionPanel.addView(input, params);
        return input;
    }

    private Button action(LinearLayout row, String label, Runnable click) {
        Button button = TvUi.button(this, label);
        button.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.rightMargin = TvUi.dp(this, 8);
        row.addView(button, params);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private void connect() {
        String host = hostInput.getText().toString().trim();
        String share = shareInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String domain = "";
        int slash = user.indexOf('\\');
        if (slash >= 0) { domain = user.substring(0, slash); user = user.substring(slash + 1); }
        final String login = user, realm = domain;
        int request = ++requestId;
        connectButton.setEnabled(false);
        status.setText("Подключаюсь к общей папке…");
        worker.execute(() -> {
            try {
                SmbConnection session = SmbConnection.connect(host, share, realm, login, password);
                runOnUiThread(() -> {
                    if (request != requestId || isFinishing()) return;
                    getSharedPreferences("network", MODE_PRIVATE).edit()
                        .putString("host", host).putString("share", share)
                        .putString("user", userInput.getText().toString().trim()).apply();
                    passwordInput.setText("");
                    connectButton.setEnabled(true);
                    connectionPanel.setVisibility(View.GONE);
                    openFolder(session.rootUrl, share);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != requestId || isFinishing()) return;
                    connectButton.setEnabled(true);
                    status.setText("Нет соединения или доступа. Проверьте адрес, ресурс, логин и SMB 2 на сервере.");
                });
            }
        });
    }

    private void openFolder(String url, String label) {
        SmbConnection session = SmbConnection.active();
        if (session == null) { connectionPanel.setVisibility(View.VISIBLE); return; }
        folderUrl = url;
        query = "";
        title.setText(label);
        entries.clear();
        visible.clear();
        adapter.notifyDataSetChanged();
        int request = ++requestId;
        status.setText("Читаю сетевую папку…");
        worker.execute(() -> {
            try {
                List<SmbConnection.Entry> found = session.list(url);
                runOnUiThread(() -> {
                    if (request != requestId || isFinishing()) return;
                    entries.addAll(found);
                    applyFilter();
                    if (!visible.isEmpty()) { list.setSelection(0); list.requestFocus(); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != requestId || isFinishing()) return;
                    status.setText("Не удалось открыть папку. Проверьте сеть и доступ; нажмите «Обновить».");
                });
            }
        });
    }

    private void applyFilter() {
        visible.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (SmbConnection.Entry entry : entries) {
            if ((entry.directory || entry.video) && entry.name.toLowerCase(Locale.ROOT).contains(needle))
                visible.add(entry);
        }
        adapter.notifyDataSetChanged();
        status.setText(visible.isEmpty() ? "В этой папке нет видео или подпапок" : "Папок и видео: " + visible.size());
    }

    private void askSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Название в текущей папке");
        input.setText(query);
        new android.app.AlertDialog.Builder(this).setTitle("Поиск в папке")
            .setView(input).setNegativeButton("Сбросить", (dialog, which) -> { query = ""; applyFilter(); })
            .setPositiveButton("Найти", (dialog, which) -> {
                query = input.getText().toString().trim(); applyFilter();
            }).show();
    }

    private void goUp() {
        SmbConnection session = SmbConnection.active();
        if (session == null || folderUrl == null || folderUrl.equals(session.rootUrl)) { finish(); return; }
        String withoutSlash = folderUrl.endsWith("/") ? folderUrl.substring(0, folderUrl.length() - 1) : folderUrl;
        int last = withoutSlash.lastIndexOf('/');
        String parent = withoutSlash.substring(0, last + 1);
        if (!parent.startsWith(session.rootUrl)) parent = session.rootUrl;
        openFolder(parent, parent.equals(session.rootUrl) ? session.share : "Папки сети");
    }

    private void play(SmbConnection.Entry selected) {
        ArrayList<String> uris = new ArrayList<>(), titles = new ArrayList<>();
        int index = -1;
        for (SmbConnection.Entry entry : entries) {
            if (!entry.video || uris.size() >= 300) continue;
            if (entry == selected) index = uris.size();
            uris.add(entry.url);
            titles.add(entry.name);
        }
        if (index < 0) return;
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putStringArrayListExtra("uris", uris);
        intent.putStringArrayListExtra("titles", titles);
        intent.putExtra("index", index);
        startActivity(intent);
    }

    @Override public void onBackPressed() {
        SmbConnection session = SmbConnection.active();
        if (session != null && folderUrl != null && !folderUrl.equals(session.rootUrl)) goUp();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        ++requestId;
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class NetworkAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView row;
            if (recycled instanceof TextView) row = (TextView) recycled;
            else {
                row = TvUi.text(NetworkBrowserActivity.this, "", 18, TvUi.TEXT);
                int radius = TvUi.dp(NetworkBrowserActivity.this, 6);
                StateListDrawable background = new StateListDrawable();
                background.addState(new int[]{android.R.attr.state_selected}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{android.R.attr.state_focused}, TvUi.shape(TvUi.ACCENT, radius));
                background.addState(new int[]{}, TvUi.shape(0xff142431, radius));
                row.setBackground(background);
                row.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {android.R.attr.state_focused}, {}},
                    new int[]{TvUi.BG, TvUi.BG, TvUi.TEXT}));
                row.setPadding(TvUi.dp(NetworkBrowserActivity.this, 16), 0, TvUi.dp(NetworkBrowserActivity.this, 16), 0);
                row.setMinHeight(TvUi.dp(NetworkBrowserActivity.this, 47));
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
            }
            SmbConnection.Entry entry = visible.get(position);
            row.setText((entry.directory ? "▸   " : "▶   ") + entry.name);
            return row;
        }
    }
}
