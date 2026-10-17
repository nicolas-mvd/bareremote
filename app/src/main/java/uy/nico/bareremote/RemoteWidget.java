package uy.nico.bareremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.RemoteViews;

/** Widget taps start a temporary foreground session; no scheduled widget updates. */
public final class RemoteWidget extends AppWidgetProvider {
    private static final String ACTION_KEY = "uy.nico.bareremote.WIDGET_KEY";
    private static final String EXTRA_KEY = "keycode";
    private static volatile String lastStatus;
    private static final Handler statusHandler = new Handler(Looper.getMainLooper());
    private static final long STATUS_IDLE_MS = 1000L;
    private static Context pendingContext;
    private static Boolean renderedPaired;
    private static final Runnable publishStatus = new Runnable() {
        @Override public void run() {
            Context context = pendingContext;
            pendingContext = null;
            if (context == null) return;
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, RemoteWidget.class));
            if (ids.length == 0) return;
            boolean paired = RemoteEngine.get(context).isPaired();
            if (renderedPaired == null || renderedPaired.booleanValue() != paired) {
                render(context, manager, ids, null);
                return;
            }
            // Status carries no click-listener or dimension changes during input.
            RemoteViews status = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
            setStatus(status, message(paired));
            setPictureMode(context, status, paired);
            manager.partiallyUpdateAppWidget(ids, status);
        }
    };

    private static final int[] BUTTONS = {
        R.id.widget_up, R.id.widget_down, R.id.widget_left, R.id.widget_right,
        R.id.widget_ok, R.id.widget_back, R.id.widget_home,
        R.id.widget_volume_down, R.id.widget_mute, R.id.widget_volume_up,
        R.id.widget_input, R.id.widget_power
    };
    private static final int[] KEYS = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_POWER
    };
    private static final String[] LABELS = {
        "Up", "Down", "Left", "Right", "OK", "Back", "Home",
        "Volume down", "Mute", "Volume up", "Input", "Power"
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
            lastStatus = "Starting…";
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            render(app, manager, manager.getAppWidgetIds(new ComponentName(app, RemoteWidget.class)), null);
            app.startForegroundService(WidgetRemoteService.keyIntent(app, key));
        } catch (RuntimeException denied) {
            updateAll(app, "Open app to connect");
        }
    }

    static boolean supportedKey(int key) {
        for (int supported : KEYS) if (supported == key) return true;
        // Existing widget PendingIntents can survive an APK update.
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return true;
        return false;
    }

    static String keyLabel(int key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == key) return LABELS[i];
        return key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? "Play / pause" : "Control";
    }

    public static void updateAll(Context context) {
        updateAll(context, null);
    }

    public static void updateAll(Context context, String status) {
        final Context app = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            statusHandler.post(() -> updateAll(app, status));
            return;
        }
        if (status != null) lastStatus = status;
        boolean paired = RemoteEngine.get(app).isPaired();
        if (!paired) lastStatus = null;
        // A pairing-state change must replace unpaired open-app intents with controls,
        // or remove control intents after Forget. Ordinary status updates do neither.
        if (renderedPaired != null && renderedPaired.booleanValue() != paired) {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            render(app, manager, manager.getAppWidgetIds(new ComponentName(app, RemoteWidget.class)), null);
            return;
        }
        pendingContext = app;
        statusHandler.removeCallbacks(publishStatus);
        statusHandler.postDelayed(publishStatus, STATUS_IDLE_MS);
    }

    private static String message(boolean paired) {
        return !paired ? "Tap to pair your TV" : lastStatus != null ? lastStatus : "Tap to connect";
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids,
            String status) {
        // An initial update, resize or pairing change supersedes queued status updates.
        statusHandler.removeCallbacks(publishStatus);
        pendingContext = null;
        if (status != null) lastStatus = status;
        boolean paired = RemoteEngine.get(context).isPaired();
        if (!paired) lastStatus = null;
        renderedPaired = paired;
        String message = message(paired);
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            // One sized layout allows Android's partial-update cache to merge status
            // actions correctly. Multi-size RemoteViews keep actions in child variants,
            // which partial updates do not merge into. Resize callbacks update dimensions.
            float width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 288);
            float height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 476);
            manager.updateAppWidget(id, makeViews(context, paired, message, width, height));
        }
    }

    private static void setStatus(RemoteViews views, String message) {
        views.setContentDescription(R.id.widget_header,
                "BareRemote. " + message + ". Open remote and connection settings.");
        views.setContentDescription(R.id.widget_sync, "Sync: reconnect to TV. " + message);
    }

    private static void setPictureMode(Context context, RemoteViews views, boolean paired) {
        String state = paired ? RemoteEngine.get(context).pictureState() : "unknown";
        int icon = R.drawable.remote_icon_mode_unknown;
        String description;
        if (!paired) {
            description = "Pair your TV in BareRemote to use Day/Night.";
        } else if ("day".equals(state)) {
            icon = R.drawable.remote_icon_sun;
            description = "Day last confirmed on TV. Tap to toggle TV mode.";
        } else if ("night".equals(state)) {
            icon = R.drawable.remote_icon_moon;
            description = "Night last confirmed on TV. Tap to toggle TV mode.";
        } else if ("custom".equals(state)) {
            description = "Custom picture levels last confirmed on TV. Tap to toggle TV mode.";
        } else {
            description = "Day/Night state unknown. Sync to check or tap to toggle TV mode.";
        }
        views.setImageViewResource(R.id.widget_mode, icon);
        views.setContentDescription(R.id.widget_mode, description);
    }

    private static RemoteViews makeViews(Context context, boolean paired, String message,
            float width, float height) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
        setStatus(views, message);
        setPictureMode(context, views, paired);
        if (Build.VERSION.SDK_INT >= 31) {
            // 24 dp outer padding + 48 dp header + 16 dp pad gaps + 140 dp bottom controls.
            float diameter = Math.max(180, Math.min(420, Math.min(width - 24, height - 228)));
            setSize(views, R.id.widget_dpad, diameter, diameter);
            float third = diameter / 3;
            views.setViewLayoutHeight(R.id.widget_up, third, TypedValue.COMPLEX_UNIT_DIP);
            views.setViewLayoutHeight(R.id.widget_down, third, TypedValue.COMPLEX_UNIT_DIP);
            setSize(views, R.id.widget_left, third, third);
            setSize(views, R.id.widget_right, third, third);
            setSize(views, R.id.widget_ok, diameter * 0.39f, diameter * 0.39f);
        }

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_header, openApp);
        PendingIntent sync = paired ? PendingIntent.getForegroundService(context, 1001,
                WidgetRemoteService.syncIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : openApp;
        views.setOnClickPendingIntent(R.id.widget_sync, sync);
        PendingIntent picture = paired ? PendingIntent.getForegroundService(context, 1005,
                WidgetRemoteService.toggleModeIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : openApp;
        views.setOnClickPendingIntent(R.id.widget_mode, picture);
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
        return views;
    }

    private static void setSize(RemoteViews views, int id, float width, float height) {
        views.setViewLayoutWidth(id, width, TypedValue.COMPLEX_UNIT_DIP);
        views.setViewLayoutHeight(id, height, TypedValue.COMPLEX_UNIT_DIP);
    }
}
