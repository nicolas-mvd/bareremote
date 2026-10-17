package uy.nico.bareremote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Grants the system installer read access to exactly the verified staged APK. */
public final class UpdateApkProvider extends ContentProvider {
    static final String MIME = "application/vnd.android.package-archive";
    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null || path.size() != 2
                || !"apk".equals(path.get(0)) || !path.get(1).matches("[0-9a-f]{64}\\.apk"))
            throw new FileNotFoundException("Invalid update URI");
        String sha = getContext().getSharedPreferences("app_updates", 0).getString("staged_sha", "");
        if (!path.get(1).equals(sha + ".apk")) throw new FileNotFoundException("No staged update");
        File file = new File(new File(getContext().getCacheDir(), "updates"), path.get(1));
        if (!file.isFile() || file.length() > UpdatePolicy.MAX_APK) throw new FileNotFoundException("No staged update");
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return MIME; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "BareRemote.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException ex) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
