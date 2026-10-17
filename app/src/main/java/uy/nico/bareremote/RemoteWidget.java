package uy.nico.bareremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.RemoteViews;

import java.util.concurrent.atomic.AtomicBoolean;

/** A passive remote: work starts only when the user presses a button. */
public final class RemoteWidget extends AppWidgetProvider {
    private static final String ACTION_KEY = "uy.nico.bareremote.WIDGET_KEY";
    private static final String EXTRA_KEY = "keycode";
    private static final long RECEIVER_TIMEOUT_MS = 8000L;

    private static final int[] BUTTONS = {
        R.id.widget_up, R.id.widget_down, R.id.widget_left, R.id.widget_right,
        R.id.widget_ok, R.id.widget_back, R.id.widget_home,
        R.id.widget_volume_down, R.id.widget_mute, R.id.widget_volume_up,
        R.id.widget_play, R.id.widget_power
    };
    private static final int[] KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_POWER
    };
    private static final String[] LABELS = {
        "Up", "Down", "Left", "Right", "OK", "Back", "Home",
        "Volume down", "Mute", "Volume up", "Play / pause", "Power"
    };

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids, null);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int id, Bundle options) {
        render(context, manager, new int[] { id }, null);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION_KEY.equals(intent.getAction())) {
            super.onReceive(context, intent);
            return;
        }
        final int key = intent.getIntExtra(EXTRA_KEY, KeyEvent.KEYCODE_UNKNOWN);
        if (!supportedKey(key)) return;

        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        final AtomicBoolean finished = new AtomicBoolean();
        final Handler handler = new Handler(Looper.getMainLooper());
        // The engine has its own shorter network budget. This also releases the
        // receiver if a future engine regression fails to invoke its callback.
        final Runnable timeout = () -> finish(app, pending, finished,
                "Timed out · open app to reconnect");
        handler.postDelayed(timeout, RECEIVER_TIMEOUT_MS);
        try {
            if (!RemoteEngine.get(app).isPaired()) {
                handler.removeCallbacks(timeout);
                finish(app, pending, finished, "Open app to pair your TV");
                return;
            }
            updateAll(app, "Sending · " + keyLabel(key));
            RemoteEngine.get(app).sendWidgetKey(key, error -> {
                handler.removeCallbacks(timeout);
                finish(app, pending, finished,
                        error == null ? "Sent · " + keyLabel(key) : "Open app to reconnect");
            });
        } catch (RuntimeException error) {
            handler.removeCallbacks(timeout);
            finish(app, pending, finished, "Open app to reconnect");
        }
    }

    private static boolean supportedKey(int key) {
        for (int supported : KEYS) if (supported == key) return true;
        return false;
    }

    private static String keyLabel(int key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == key) return LABELS[i];
        return "Control";
    }

    private static void finish(Context context, PendingResult pending,
            AtomicBoolean finished, String status) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            updateAll(context, status);
        } finally {
            pending.finish();
        }
    }

    public static void updateAll(Context context) {
        updateAll(context, null);
    }

    public static void updateAll(Context context, String status) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, RemoteWidget.class));
        if (ids.length != 0) render(context, manager, ids, status);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids,
            String status) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
        boolean paired = RemoteEngine.get(context).isPaired();
        String message = !paired ? "Tap to pair your TV"
                : status != null ? status : "TV saved · tap to open";
        views.setTextViewText(R.id.widget_status, message);
        views.setContentDescription(R.id.widget_header, "BareRemote. " + message
                + ". Open remote and connection settings.");

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_header, openApp);
        for (int i = 0; i < BUTTONS.length; i++) {
            PendingIntent action = openApp;
            if (paired) {
                Intent command = new Intent(context, RemoteWidget.class)
                        .setAction(ACTION_KEY).putExtra(EXTRA_KEY, KEYS[i])
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                action = PendingIntent.getBroadcast(context, KEYS[i], command,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            // An unpaired widget opens the activity directly, without a broadcast
            // trampoline or background activity launch.
            views.setOnClickPendingIntent(BUTTONS[i], action);
        }
        manager.updateAppWidget(ids, views);
    }
}
