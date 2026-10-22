package com.aurora.tvplayer;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class VideoLibrary {
    static final class Entry {
        final String name;
        final Uri uri;
        Entry(String name, Uri uri) { this.name = name; this.uri = uri; }
    }

    private static final String[] EXTENSIONS = {
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".mpeg", ".mpg", ".flv", ".3gp", ".vob", ".mts", ".m2t", ".wmv", ".asf"
    };

    static List<Entry> scan(Context context, Uri treeUri) {
        List<Entry> entries = new ArrayList<>();
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root != null) walk(root, entries, 0);
        entries.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return entries;
    }

    static List<Entry> scanDirectFile(File root) {
        List<Entry> entries = new ArrayList<>();
        File[] files = root.listFiles();
        if (files == null) return entries;
        for (File file : files) {
            if (file.isFile() && isVideoName(file.getName()))
                entries.add(new Entry(file.getName(), Uri.fromFile(file)));
        }
        entries.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static void walk(DocumentFile folder, List<Entry> output, int depth) {
        if (depth > 8 || output.size() >= 3000) return;
        for (DocumentFile file : folder.listFiles()) {
            if (file.isDirectory()) walk(file, output, depth + 1);
            else if (file.isFile() && isVideo(file)) output.add(new Entry(file.getName() == null ? "Видео" : file.getName(), file.getUri()));
            if (output.size() >= 3000) break;
        }
    }

    static boolean isVideo(DocumentFile file) {
        String type = file.getType();
        if (type != null && type.startsWith("video/")) return true;
        return isVideoName(file.getName());
    }

    static boolean isVideoName(String name) {
        if (name == null) return false;
        name = name.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) if (name.endsWith(ext)) return true;
        return false;
    }

    static String displayName(Context context, Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentFile file = DocumentFile.fromSingleUri(context, uri);
                if (file != null && file.getName() != null) return file.getName();
            }
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "Видео" : Uri.decode(segment);
    }

    private VideoLibrary() {}
}
