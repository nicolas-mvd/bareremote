package uy.nico.bareremote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/** Small native screen; the Android installer always asks before replacing the app. */
public final class UpdateActivity extends Activity {
    private AppUpdates updates;
    private AppUpdates.Release release;
    private TextView status;
    private Button action;
    private Button check;
    private File ready;
    private boolean working;
    private boolean pausedWork;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        updates = new AppUpdates(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density + .5f);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(0xff252627);
        TextView title = text(AppUpdates.APP_NAME + " updates", 28);
        content.addView(title);
        content.addView(text("Installed: " + updates.installedName(), 16));
        status = text("Checking for updates…", 18);
        status.setPadding(0, pad, 0, pad);
        content.addView(status);
        action = new Button(this);
        action.setText("Download update");
        action.setVisibility(android.view.View.GONE);
        action.setOnClickListener(v -> { if (ready == null) download(); else install(); });
        content.addView(action);
        check = new Button(this);
        check.setText("Check again");
        check.setOnClickListener(v -> check());
        content.addView(check);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(v -> finish());
        content.addView(back);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff252627);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(content);
        setContentView(scroll);
        check();
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(Color.WHITE);
        return text;
    }

    private void check() {
        ready = null;
        working = true;
        action.setVisibility(android.view.View.GONE);
        check.setEnabled(false);
        status.setText("Checking for updates…");
        updates.check(true, (found, error) -> {
            working = false;
            check.setEnabled(true);
            release = found;
            if (error != null) {
                status.setText(error + (found == null ? "" : "\nPreviously found: " + found.name));
            } else status.setText(found == null ? "You’re up to date." : "Version " + found.name + " is available.");
            action.setText("Download update");
            action.setVisibility(found == null ? android.view.View.GONE : android.view.View.VISIBLE);
            action.setEnabled(true);
            if (found != null) action.requestFocus();
        });
    }

    private void download() {
        if (release == null || working) return;
        working = true;
        status.setText("Downloading and verifying " + release.name + "…");
        action.setEnabled(false); check.setEnabled(false);
        updates.download(release, (file, error) -> {
            working = false;
            check.setEnabled(true); action.setEnabled(true);
            ready = file;
            if (error != null) status.setText(error);
            else {
                status.setText("Update verified. Android will ask you to confirm installation.");
                action.setText(getPackageManager().canRequestPackageInstalls() ? "Install update" : "Allow installs");
                action.requestFocus();
            }
        });
    }

    private void install() {
        if (ready == null || !ready.isFile()) {
            ready = null; status.setText("The downloaded update is no longer available. Download it again.");
            action.setText("Download update"); return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow " + AppUpdates.APP_NAME + " to install updates, then return here and choose Install update.");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException | SecurityException ex) {
                status.setText("Open Android Settings → Install unknown apps and allow " + AppUpdates.APP_NAME + ". Then return here.");
            }
            return;
        }
        Uri uri = Uri.parse("content://" + getPackageName() + ".updates/apk/" + ready.getName());
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(uri, UpdateApkProvider.MIME);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("Verified update", uri));
        try { startActivity(install); }
        catch (ActivityNotFoundException | SecurityException ex) {
            status.setText("This device has no accessible Android package installer. Install the official APK using ADB instead.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (ready != null) action.setText(getPackageManager().canRequestPackageInstalls() ? "Install update" : "Allow installs");
        if (pausedWork) {
            pausedWork = false;
            status.setText("Update check or download paused. Choose Check again to retry.");
            check.setEnabled(true); action.setEnabled(true);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (working) { pausedWork = true; working = false; }
        updates.cancel();
    }
    @Override protected void onDestroy() { updates.cancel(); super.onDestroy(); }
}
