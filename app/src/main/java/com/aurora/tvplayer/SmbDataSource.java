package com.aurora.tvplayer;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.io.InputStream;
import jcifs.smb.SmbFile;

/** Seekable, read-only Media3 source: no temporary copy of a 4K movie is made. */
final class SmbDataSource extends BaseDataSource {
    static final DataSource.Factory FACTORY = SmbDataSource::new;
    private SmbFile file;
    private InputStream input;
    private Uri uri;
    private long remaining;
    private boolean opened;

    private SmbDataSource() { super(true); }

    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        SmbConnection session = SmbConnection.active();
        if (session == null) throw new IOException("Подключение SMB завершено. Откройте сетевую папку снова.");
        try {
            file = session.file(spec.uri.toString());
            long size = file.length();
            if (spec.position > size) throw new IOException("Позиция за концом файла");
            input = file.openInputStream();
            long toSkip = spec.position;
            while (toSkip > 0) {
                long skipped = input.skip(toSkip);
                if (skipped <= 0) {
                    if (input.read() == -1) throw new IOException("Не удалось перейти к позиции файла");
                    skipped = 1;
                }
                toSkip -= skipped;
            }
            remaining = spec.length == C.LENGTH_UNSET ? size - spec.position
                : Math.min(spec.length, size - spec.position);
            uri = spec.uri;
            opened = true;
            transferStarted(spec);
            return remaining;
        } catch (Exception e) {
            try { close(); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e instanceof IOException ? (IOException) e : new IOException("Ошибка SMB", e);
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int count = input.read(buffer, offset, (int) Math.min(length, remaining));
        if (count == -1) return C.RESULT_END_OF_INPUT;
        remaining -= count;
        bytesTransferred(count);
        return count;
    }

    @Override public Uri getUri() { return uri; }

    @Override public void close() throws IOException {
        IOException failure = null;
        try { if (input != null) input.close(); }
        catch (Exception e) { failure = new IOException(e); }
        finally {
            input = null;
            if (file != null) file.close();
            file = null;
            uri = null;
            if (opened) { opened = false; transferEnded(); }
        }
        if (failure != null) throw failure;
    }
}
