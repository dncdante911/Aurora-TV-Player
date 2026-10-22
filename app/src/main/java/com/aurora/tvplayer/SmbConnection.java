package com.aurora.tvplayer;

import android.net.Uri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;

/** One read-only SMB share for the lifetime of this app process. Passwords are never persisted. */
final class SmbConnection {
    static final class Entry {
        final String name, url;
        final boolean directory, video;
        Entry(String name, String url, boolean directory) {
            this.name = name;
            this.url = url;
            this.directory = directory;
            this.video = !directory && VideoLibrary.isVideoName(name);
        }
    }

    private static volatile SmbConnection active;
    final String host, share, rootUrl;
    private final CIFSContext context;

    private SmbConnection(String host, String share, CIFSContext context) {
        this.host = host;
        this.share = share;
        this.context = context;
        this.rootUrl = "smb://" + host + "/" + Uri.encode(share) + "/";
    }

    static SmbConnection active() { return active; }

    static boolean canOpen(String url) {
        SmbConnection session = active;
        return session != null && url != null && url.startsWith(session.rootUrl);
    }

    static SmbConnection connect(String host, String share, String domain, String user, String password)
            throws Exception {
        if (!host.matches("[A-Za-z0-9._-]+") || share.isEmpty()
                || share.contains("/") || share.contains("\\") || share.contains(":")
                || share.equals(".") || share.equals(".."))
            throw new IllegalArgumentException("Введите адрес сервера и имя общей папки без слэшей.");
        Properties settings = new Properties();
        settings.setProperty("jcifs.smb.client.minVersion", "SMB202");
        settings.setProperty("jcifs.smb.client.maxVersion", "SMB210");
        settings.setProperty("jcifs.smb.client.responseTimeout", "12000");
        settings.setProperty("jcifs.smb.client.connTimeout", "12000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(settings));
        CIFSContext authenticated = user.isEmpty() && password.isEmpty()
            ? base.withGuestCrendentials()
            : base.withCredentials(new NtlmPasswordAuthentication(base, domain, user, password));
        SmbConnection candidate = new SmbConnection(host, share, authenticated);
        try {
            candidate.list(candidate.rootUrl); // Verify access before replacing a working connection.
        } catch (IOException guestError) {
            if (!user.isEmpty() || !password.isEmpty()) throw guestError;
            candidate = new SmbConnection(host, share, base.withAnonymousCredentials());
            candidate.list(candidate.rootUrl);
        }
        active = candidate;
        return candidate;
    }

    List<Entry> list(String url) throws IOException {
        if (url == null || !url.startsWith(rootUrl)) throw new IOException("Папка вне подключённого ресурса");
        ArrayList<Entry> result = new ArrayList<>();
        try (SmbFile folder = new SmbFile(url, context)) {
            for (SmbFile child : folder.listFiles()) {
                try (SmbFile item = child) {
                    String name = item.getName();
                    boolean directory = item.isDirectory();
                    if (directory && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    result.add(new Entry(name, item.getPath(), directory));
                }
            }
        } catch (Exception e) {
            throw new IOException("Не удалось прочитать общую папку", e);
        }
        Collections.sort(result, Comparator.comparing((Entry e) -> !e.directory)
            .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    SmbFile file(String url) throws IOException {
        if (url == null || !url.startsWith(rootUrl)) throw new IOException("Файл вне подключённого ресурса");
        return new SmbFile(url, context);
    }

    private SmbConnection() { throw new AssertionError(); }
}
