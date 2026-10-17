package uy.nico.bareremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Timer;
import java.util.TimerTask;

/** Bounded, opt-in APK downloads. No service, alarms, polling or permanent worker. */
final class AppUpdates {
    static final String APP_NAME = "BareRemote";
    static final String METADATA_URL = "https://raw.githubusercontent.com/nicolas-mvd/bareremote/main/update.json";
    private static final long CHECK_INTERVAL = 24 * 60 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Operation active;

    interface Callback { void complete(Release release, String error); }
    interface DownloadCallback { void complete(File file, String error); }

    static final class Release {
        final int versionCode;
        final String name;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final long size;
        Release(int code, String name, String url, String sha, long size) {
            UpdatePolicy.validate(code, name, url, sha, size);
            versionCode = code; this.name = name; versionName = name;
            apkUrl = url; sha256 = sha; this.size = size;
        }
    }

    AppUpdates(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
    }

    static void open(Activity activity) { activity.startActivity(new Intent(activity, UpdateActivity.class)); }

    Release available() {
        try {
            Release release = parse(preferences.getString("metadata", ""));
            return release.versionCode > installedCode() ? release : null;
        } catch (Exception ignored) { return null; }
    }

    String installedName() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ex) { return "unknown"; }
    }

    private long installedCode() throws PackageManager.NameNotFoundException {
        return code(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    void check(boolean force, Callback callback) {
        cancel();
        Operation operation = begin();
        long age = System.currentTimeMillis() - preferences.getLong("attempt", 0);
        if (!force && age >= 0 && age < CHECK_INTERVAL) {
            deliver(operation, () -> callback.complete(available(), preferences.getString("error", null)));
            return;
        }
        new Thread(() -> {
            operation.deadline(25000);
            Release release = null;
            String error = null;
            try {
                byte[] bytes = fetchMetadata(operation);
                String json = new String(bytes, StandardCharsets.UTF_8);
                Release fetched = parse(json);
                operation.ensure();
                synchronized (lock) {
                    operation.ensure();
                    if (active != operation) throw new IOException("Update superseded");
                    preferences.edit().putLong("attempt", System.currentTimeMillis())
                            .putString("metadata", json).remove("error").apply();
                }
                if (fetched.versionCode > installedCode()) release = fetched;
            } catch (Exception ex) {
                error = "Could not check for updates. Check your connection and try again.";
                synchronized (lock) {
                    if (active == operation && !operation.cancelled) preferences.edit()
                            .putLong("attempt", System.currentTimeMillis()).putString("error", error).apply();
                }
                release = available();
            } finally { operation.close(); }
            final Release result = release;
            final String problem = error;
            deliver(operation, () -> callback.complete(result, problem));
        }, "BareRemote-update-check").start();
    }

    void download(Release release, DownloadCallback callback) {
        cancel();
        Operation operation = begin();
        new Thread(() -> {
            operation.deadline(90000);
            File partial = null;
            File ready = null;
            String error = null;
            try {
                UpdatePolicy.validate(release.versionCode, release.name, release.apkUrl, release.sha256, release.size);
                if (release.versionCode <= installedCode()) throw new IOException("Version is not newer");
                File directory = new File(context.getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update directory");
                partial = File.createTempFile("download-", ".part", directory);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                HttpURLConnection connection = connect(operation, release.apkUrl, true);
                long length = connection.getContentLengthLong();
                if (length != -1 && length != release.size) throw new IOException("Wrong download size");
                long total = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    operation.input(input);
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        operation.ensure();
                        total += count;
                        if (total > release.size || total > UpdatePolicy.MAX_APK) throw new IOException("Download too large");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                operation.ensure();
                if (total != release.size || !UpdatePolicy.digestMatches(release.sha256, digest.digest()))
                    throw new IOException("Download verification failed");
                verifyPackage(partial, release);
                operation.ensure();
                File destination = new File(directory, release.sha256 + ".apk");
                synchronized (lock) {
                    operation.ensure();
                    if (active != operation) throw new IOException("Update superseded");
                    if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace staged update");
                    if (!partial.renameTo(destination)) throw new IOException("Cannot stage update");
                    if (!preferences.edit().putString("staged_sha", release.sha256).commit()) {
                        destination.delete();
                        throw new IOException("Cannot save staged update");
                    }
                    ready = destination;
                }
                File[] files = directory.listFiles();
                if (files != null) for (File file : files)
                    if (!file.equals(destination) && file.getName().matches("[0-9a-f]{64}\\.apk")) file.delete();
            } catch (Exception ex) {
                error = "Download could not be verified. Nothing was installed. Try again.";
            } finally {
                operation.close();
                if (partial != null && partial.exists()) partial.delete();
            }
            final File result = ready;
            final String problem = error;
            deliver(operation, () -> callback.complete(result, problem));
        }, "BareRemote-update-download").start();
    }

    @SuppressWarnings("deprecation")
    private void verifyPackage(File apk, Release release) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !UpdatePolicy.packageMatches(context.getPackageName(), candidate.packageName,
                code(installed), code(candidate), release.versionCode, signers(installed), signers(candidate)))
            throw new IOException("Update package, version or signature mismatch");
    }

    @SuppressWarnings("deprecation")
    private static byte[][] signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                : info.signatures;
        if (signatures == null) return null;
        byte[][] bytes = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) bytes[i] = signatures[i].toByteArray();
        return bytes;
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Release parse(String json) throws IOException {
        UpdatePolicy.Metadata data = UpdatePolicy.parse(json);
        return new Release(data.code, data.name, data.url, data.sha, data.size);
    }

    private byte[] fetchMetadata(Operation operation) throws Exception {
        HttpURLConnection connection = connect(operation, METADATA_URL, false);
        long size = connection.getContentLengthLong();
        if (size > UpdatePolicy.MAX_METADATA) throw new IOException("Metadata too large");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            operation.input(input);
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) {
                operation.ensure();
                if (output.size() + count > UpdatePolicy.MAX_METADATA) throw new IOException("Metadata too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private HttpURLConnection connect(Operation operation, String address, boolean download) throws Exception {
        for (int redirects = 0; redirects <= 4; redirects++) {
            operation.ensure();
            if (download ? !UpdatePolicy.downloadHost(address) : !METADATA_URL.equals(address))
                throw new IOException("Update host rejected");
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "BareRemote-Updater");
            operation.connection(connection);
            int status = connection.getResponseCode();
            operation.ensure();
            if (status == 200) return connection;
            if (download && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Missing redirect");
                address = new URL(new URL(address), location).toString();
                connection.disconnect();
            } else throw new IOException("Update request failed");
        }
        throw new IOException("Too many update redirects");
    }

    void cancel() {
        Operation operation;
        synchronized (lock) {
            operation = active; active = null;
            if (operation != null) operation.cancelled = true;
        }
        if (operation != null) operation.cancel();
    }

    private Operation begin() {
        Operation operation = new Operation();
        synchronized (lock) { active = operation; }
        return operation;
    }

    private void deliver(Operation operation, Runnable callback) {
        main.post(() -> {
            synchronized (lock) {
                if (active != operation || operation.cancelled) return;
                active = null;
            }
            callback.run();
        });
    }

    private static final class Operation {
        volatile boolean cancelled;
        volatile boolean timedOut;
        private HttpURLConnection connection;
        private InputStream input;
        private Timer timer;
        synchronized void deadline(long millis) {
            if (cancelled) return;
            timer = new Timer("BareRemote-update-deadline", true);
            timer.schedule(new TimerTask() {
                @Override public void run() { timedOut = true; close(); }
            }, millis);
        }
        synchronized void connection(HttpURLConnection connection) throws IOException {
            if (cancelled || timedOut) { connection.disconnect(); ensure(); }
            this.connection = connection;
        }
        synchronized void input(InputStream input) throws IOException {
            if (cancelled || timedOut) { input.close(); ensure(); }
            this.input = input;
        }
        void ensure() throws IOException {
            if (cancelled || timedOut) throw new IOException("Update stopped");
        }
        void cancel() {
            cancelled = true;
            // HttpURLConnection cleanup may wait for its network reader; never do that on UI.
            new Thread(this::close, "BareRemote-update-cancel").start();
        }
        void close() {
            HttpURLConnection oldConnection;
            InputStream oldInput;
            synchronized (this) {
                if (timer != null) { timer.cancel(); timer = null; }
                oldConnection = connection; connection = null;
                oldInput = input; input = null;
            }
            if (oldConnection != null) oldConnection.disconnect();
            if (oldInput != null) { try { oldInput.close(); } catch (IOException ignored) { } }
        }
    }
}
