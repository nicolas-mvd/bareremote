package uy.nico.bareremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/** A user-started remote session that ends one minute after the last widget tap. */
public final class WidgetRemoteService extends Service {
    static final String ACTION_KEY = "uy.nico.bareremote.WIDGET_KEY";
    static final String ACTION_SYNC = "uy.nico.bareremote.WIDGET_SYNC";
    static final String ACTION_PICTURE_MODE = "uy.nico.bareremote.WIDGET_PICTURE_MODE";
    static final String EXTRA_NIGHT = "night";
    static final String ACTION_STOP = "uy.nico.bareremote.WIDGET_STOP";
    static final String EXTRA_KEY = "keycode";
    private static final String CHANNEL = "widget_remote";
    private static final int NOTIFICATION = 1;
    private static final long IDLE_MS = 60000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private RemoteEngine engine;
    private boolean sessionActive;
    private boolean foregroundStarted;
    private long actionSequence;
    private long idleDeadline;
    private int latestStartId;
    private String endStatus;
    private final Runnable idleStop = new Runnable() {
        @Override public void run() {
            if (!sessionActive) return;
            long remaining = idleDeadline - SystemClock.elapsedRealtime();
            if (remaining > 0) main.postDelayed(this, remaining);
            else endSession("Tap to connect");
        }
    };

    static Intent keyIntent(Context context, int key) {
        return new Intent(context, WidgetRemoteService.class)
                .setAction(ACTION_KEY).putExtra(EXTRA_KEY, key);
    }

    static Intent syncIntent(Context context) {
        return new Intent(context, WidgetRemoteService.class).setAction(ACTION_SYNC);
    }

    static Intent pictureModeIntent(Context context, boolean night) {
        return new Intent(context, WidgetRemoteService.class)
                .setAction(ACTION_PICTURE_MODE).putExtra(EXTRA_NIGHT, night);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Widget remote session",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the widget keeps the TV connection ready for one minute.");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            endSession("Tap to connect");
            return START_NOT_STICKY;
        }
        // A widget PendingIntent starts this as a foreground service. Promote before
        // touching the engine; notification permission is not required for this call.
        try {
            // Repeated taps keep the existing foreground session. Reposting the same
            // notification for every key wastes binder work and hits system rate limits.
            if (!foregroundStarted) {
                Notification notification = notification();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(NOTIFICATION, notification);
                }
                foregroundStarted = true;
            }
        } catch (RuntimeException denied) {
            endSession("Open app to connect");
            return START_NOT_STICKY;
        }

        int key = intent == null ? KeyEvent.KEYCODE_UNKNOWN
                : intent.getIntExtra(EXTRA_KEY, KeyEvent.KEYCODE_UNKNOWN);
        boolean pictureMode = ACTION_PICTURE_MODE.equals(action) && intent.hasExtra(EXTRA_NIGHT);
        final boolean night = pictureMode && intent.getBooleanExtra(EXTRA_NIGHT, false);
        if (!ACTION_SYNC.equals(action) && !pictureMode
                && !(ACTION_KEY.equals(action) && RemoteWidget.supportedKey(key))) {
            endSession("Tap to connect");
            return START_NOT_STICKY;
        }
        engine = RemoteEngine.get(this);
        if (!engine.isPaired()) {
            endSession("Tap to pair your TV");
            return START_NOT_STICKY;
        }
        sessionActive = true;
        endStatus = null;
        engine.setWidgetSessionActive(true);
        idleDeadline = SystemClock.elapsedRealtime() + IDLE_MS;
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_MS);
        final long sequence = ++actionSequence;
        final boolean sync = ACTION_SYNC.equals(action);
        final String label = pictureMode ? night ? "Night" : "Day" : RemoteWidget.keyLabel(key);
        RemoteWidget.updateAll(this, sync ? "Syncing…" : "Sending · " + label);
        RemoteEngine.Completion completion = error -> {
            if (!sessionActive || sequence != actionSequence) return;
            if (!engine.isPaired()) {
                endSession("Tap to pair your TV");
                return;
            }
            RemoteWidget.updateAll(this, error == null
                    ? sync ? "Connected" : pictureMode ? label + " requested" : "Sent · " + label
                    : pictureMode ? "Day/Night unavailable · open app" : "Sync to reconnect");
        };
        try {
            if (sync) engine.reconnect(completion);
            else if (pictureMode) engine.sendPictureMode(night, completion);
            else engine.sendWidgetKey(key, completion);
        } catch (RuntimeException failure) {
            completion.done("Could not send control");
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 1000, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WidgetRemoteService.class).setAction(ACTION_STOP);
        PendingIntent stopSession = PendingIntent.getService(this, 1002, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.widget_notification)
                .setContentTitle("BareRemote")
                .setContentText("Stops 1 minute after your last widget tap")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stopSession).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void endSession(String message) {
        ++actionSequence;
        sessionActive = false;
        endStatus = message;
        main.removeCallbacks(idleStop);
        if (engine != null) engine.setWidgetSessionActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        RemoteWidget.updateAll(this, message);
        stopSelf(latestStartId);
    }

    @Override public void onDestroy() {
        ++actionSequence;
        sessionActive = false;
        main.removeCallbacks(idleStop);
        if (engine != null) engine.setWidgetSessionActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        RemoteWidget.updateAll(this, endStatus == null ? "Tap to connect" : endStatus);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
