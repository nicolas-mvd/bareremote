package uy.nico.bareremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.RemoteViews;

/** Widget taps start a temporary foreground session; no scheduled widget updates. */
public final class RemoteWidget extends AppWidgetProvider {
    private static final String ACTION_KEY = "uy.nico.bareremote.WIDGET_KEY";
    private static final String EXTRA_KEY = "keycode";
    private static volatile String lastStatus;

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

        // Older installed widgets may still hold a broadcast PendingIntent. Replace
        // their views and hand the tap to the service instead of retaining a receiver.
        Context app = context.getApplicationContext();
        try {
            if (!RemoteEngine.get(app).isPaired()) {
                updateAll(app, "Tap to pair your TV");
                return;
            }
            updateAll(app, "Starting…");
            app.startForegroundService(WidgetRemoteService.keyIntent(app, key));
        } catch (RuntimeException denied) {
            updateAll(app, "Open app to connect");
        }
    }

    static boolean supportedKey(int key) {
        for (int supported : KEYS) if (supported == key) return true;
        return false;
    }

    static String keyLabel(int key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == key) return LABELS[i];
        return "Control";
    }

    public static void updateAll(Context context) {
        updateAll(context, null);
    }

    public static void updateAll(Context context, String status) {
        if (status != null) lastStatus = status;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, RemoteWidget.class));
        if (ids.length != 0) render(context, manager, ids, status);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids,
            String status) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
        boolean paired = RemoteEngine.get(context).isPaired();
        if (!paired) lastStatus = null;
        String message = !paired ? "Tap to pair your TV"
                : status != null ? status : lastStatus != null ? lastStatus : "Tap to connect";
        views.setTextViewText(R.id.widget_status, message);
        views.setContentDescription(R.id.widget_header, "BareRemote. " + message
                + ". Open remote and connection settings.");

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_header, openApp);
        PendingIntent sync = paired ? PendingIntent.getForegroundService(context, 1001,
                WidgetRemoteService.syncIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : openApp;
        views.setOnClickPendingIntent(R.id.widget_sync, sync);
        for (int i = 0; i < BUTTONS.length; i++) {
            PendingIntent action = openApp;
            if (paired) {
                Intent command = WidgetRemoteService.keyIntent(context, KEYS[i]);
                action = PendingIntent.getForegroundService(context, KEYS[i], command,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            // An unpaired widget opens the activity directly, without a broadcast
            // trampoline or background activity launch.
            views.setOnClickPendingIntent(BUTTONS[i], action);
        }
        manager.updateAppWidget(ids, views);
    }
}
