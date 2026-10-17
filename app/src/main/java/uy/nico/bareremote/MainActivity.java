package uy.nico.bareremote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A remote, immediately. No content feed, accounts, or background UI work. */
public final class MainActivity extends Activity implements RemoteEngine.Listener {
    private static final int WHITE = 0xffdadcd9;
    private static final int SURFACE = 0xff303234;
    private static final int GREEN = 0xff7bd99a;
    private static final int GREEN_INK = 0xff153d25;
    private static final int MUTED = 0xffaaaaaa;
    private final List<View> remoteButtons = new ArrayList<>();
    private RemoteEngine engine;
    private TextView stateText;
    private TextView errorText;
    private Button syncButton;
    private AlertDialog setupDialog;
    private AlertDialog codeDialog;
    private AlertDialog helpDialog;
    private EditText hostInput;
    private EditText codeInput;
    private boolean resumed;
    private boolean pendingSetup;
    private boolean pendingCode;
    private boolean pairingInProgress;
    private boolean pairingInterrupted;
    private String hostDraft = "";
    private String codeDraft = "";

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        engine = RemoteEngine.get(this);
        hostDraft = safeHost();
        if (savedState != null) {
            hostDraft = savedState.getString("hostDraft", hostDraft);
            codeDraft = savedState.getString("codeDraft", "");
            pairingInterrupted = savedState.getBoolean("pairingInProgress")
                    || savedState.getBoolean("codeOpen")
                    || savedState.getBoolean("pairingInterrupted");
            pendingSetup = savedState.getBoolean("setupOpen") || pairingInterrupted;
            codeDraft = "";
        } else {
            pendingSetup = !engine.isPaired();
        }
        createRemote();
    }

    private void createRemote() {
        LinearLayout screen = column();
        screen.setBackgroundColor(SURFACE);
        screen.setPadding(dp(12), dp(8), dp(12), dp(12));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(12) + left, dp(8) + top, dp(12) + right, dp(12) + bottom);
            return insets;
        });
        ScrollView scroll = new RemoteScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        RemoteSurface content = new RemoteSurface(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = row();
        ImageButton setup = icon(R.drawable.remote_icon_setup, "Setup");
        setup.setOnClickListener(v -> showSetup());
        header.addView(setup, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout shortcuts = row();
        shortcuts.setBackground(roundFill(GREEN, 100));
        syncButton = button("Sync", "Reconnect to saved TV");
        syncButton.setTextColor(GREEN_INK);
        syncButton.setTextSize(13);
        syncButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        syncButton.setBackground(roundStates(0x00000000, 0x2230523a, 100));
        syncButton.setPadding(dp(8), 0, dp(4), 0);
        Drawable syncIcon = getDrawable(R.drawable.remote_icon_sync).mutate();
        syncIcon.setTint(GREEN_INK);
        syncIcon.setBounds(0, 0, dp(19), dp(19));
        syncButton.setCompoundDrawables(syncIcon, null, null, null);
        syncButton.setCompoundDrawablePadding(dp(4));
        syncButton.setTooltipText("Sync: reconnect to saved TV");
        syncButton.setOnClickListener(v -> { clearError(); engine.reconnect(); });
        shortcuts.addView(syncButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        View divider = spacer();
        divider.setBackgroundColor(0x35153d25);
        shortcuts.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(26)));
        ImageButton day = modeButton(R.drawable.remote_icon_sun, "Day", false);
        ImageButton night = modeButton(R.drawable.remote_icon_moon, "Night", true);
        shortcuts.addView(day, new LinearLayout.LayoutParams(dp(48), dp(48)));
        shortcuts.addView(night, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams shortParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        shortParams.leftMargin = dp(8);
        shortParams.rightMargin = dp(8);
        header.addView(shortcuts, shortParams);
        ImageButton power = keyIcon(R.drawable.remote_icon_power, "TV power", KeyEvent.KEYCODE_POWER);
        header.addView(power, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.setHeader(header);

        LinearLayout status = column();
        stateText = text(engine.isPaired() ? "Connecting…" : "Pair a TV in Setup", 12, MUTED);
        stateText.setGravity(Gravity.CENTER);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateText.setPadding(0, dp(10), 0, 0);
        status.addView(stateText);
        errorText = text("", 13, WHITE);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(8), dp(6), dp(8), 0);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        errorText.setVisibility(View.GONE);
        status.addView(errorText, new LinearLayout.LayoutParams(-1, -2));
        content.setStatus(status);

        RemoteDpad pad = new RemoteDpad(this);
        pad.addDirection(keyIcon(R.drawable.remote_icon_up, "Up", KeyEvent.KEYCODE_DPAD_UP));
        pad.addDirection(keyIcon(R.drawable.remote_icon_right, "Right", KeyEvent.KEYCODE_DPAD_RIGHT));
        pad.addDirection(keyIcon(R.drawable.remote_icon_down, "Down", KeyEvent.KEYCODE_DPAD_DOWN));
        pad.addDirection(keyIcon(R.drawable.remote_icon_left, "Left", KeyEvent.KEYCODE_DPAD_LEFT));
        ImageButton okay = keyIcon(0, "OK. Touch and hold for a long press", KeyEvent.KEYCODE_DPAD_CENTER);
        okay.setOnLongClickListener(v -> {
            clearError();
            engine.sendLongKey(KeyEvent.KEYCODE_DPAD_CENTER);
            return true;
        });
        pad.setCenter(okay);
        content.setPad(pad);

        LinearLayout navigation = row();
        ImageButton back = keyIcon(R.drawable.remote_icon_back, "Back", KeyEvent.KEYCODE_BACK);
        ImageButton home = keyIcon(R.drawable.remote_icon_home, "TV home", KeyEvent.KEYCODE_HOME);
        ImageButton input = keyIcon(R.drawable.remote_icon_gamepad, "Input / source", KeyEvent.KEYCODE_TV_INPUT);
        addWeightedIcon(navigation, back, 1);
        addWeightedIcon(navigation, home, 1.35f);
        addWeightedIcon(navigation, input, 1);
        // Inset circular faces inside equally generous native touch targets.
        back.setBackground(new CircularButtonDrawable());
        input.setBackground(new CircularButtonDrawable());
        content.setNavigation(navigation);
        LinearLayout volume = row();
        addWeightedIcon(volume, keyIcon(R.drawable.remote_icon_mute, "Mute or unmute", KeyEvent.KEYCODE_VOLUME_MUTE), .72f);
        addWeightedIcon(volume, keyIcon(R.drawable.remote_icon_volume_down, "Volume down", KeyEvent.KEYCODE_VOLUME_DOWN), 1.2f);
        addWeightedIcon(volume, keyIcon(R.drawable.remote_icon_volume_up, "Volume up", KeyEvent.KEYCODE_VOLUME_UP), 1.2f);
        content.setVolume(volume);
        setContentView(screen);
        screen.requestApplyInsets();
        refreshTarget();
        enableControls(engine.isPaired());
    }

    private ImageButton icon(int resource, String description) {
        ImageButton button = new ImageButton(this);
        if (resource != 0) button.setImageResource(resource);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setBackground(roundStates(0xff1c1e1d, 0xff454b47, 100));
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setStateListAnimator(null);
        return button;
    }

    private ImageButton keyIcon(int resource, String description, int key) {
        ImageButton button = icon(resource, description);
        button.setOnClickListener(v -> { clearError(); engine.sendKey(key); });
        remoteButtons.add(button);
        return button;
    }

    private ImageButton modeButton(int resource, String description, boolean night) {
        ImageButton button = icon(resource, description + " · BareHome");
        button.setImageTintList(ColorStateList.valueOf(GREEN_INK));
        button.setBackground(roundStates(0x00000000, 0x2230523a, 100));
        button.setOnClickListener(v -> {
            clearError();
            engine.sendPictureMode(night);
        });
        remoteButtons.add(button);
        return button;
    }

    private void addWeightedIcon(LinearLayout row, ImageButton button, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        row.addView(button, params);
    }

    private GradientDrawable roundFill(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private StateListDrawable roundStates(int normal, int pressed, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, roundFill(pressed, radius));
        GradientDrawable focused = roundFill(normal, radius);
        focused.setStroke(dp(2), WHITE);
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[0], roundFill(normal, radius));
        return states;
    }

    private void showSetup() {
        pendingSetup = true;
        if (!resumed || setupDialog != null) return;
        pendingCode = false;
        dismissCode();
        LinearLayout body = dialogBody();
        if (pairingInterrupted) {
            TextView interrupted = text("Pairing stopped when the app left the screen. Tap Pair to request a new code.", 14, WHITE);
            interrupted.setPadding(0, 0, 0, dp(12));
            body.addView(interrupted);
        }
        body.addView(text("Use the same Wi-Fi as your TV. Find its IP address in the TV’s network settings.", 15, WHITE));
        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setHint("TV IP address");
        hostInput.setTextColor(WHITE);
        hostInput.setHintTextColor(MUTED);
        hostInput.setContentDescription("TV IPv4 address");
        hostInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(253)});
        hostInput.setText(hostDraft.isEmpty() ? safeHost() : hostDraft);
        hostInput.setSelectAllOnFocus(true);
        body.addView(hostInput, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView note = text("The TV will show a pairing code. Each phone pairs separately.", 13, MUTED);
        note.setPadding(0, dp(8), 0, 0);
        body.addView(note);
        TextView modes = text("☀ Day / ☾ Night use the saved picture levels in BareHome on your TV. Install BareHome 1.1 or later and enable its picture controls. Input opens the TV’s source menu.", 13, MUTED);
        modes.setPadding(0, dp(16), 0, dp(12));
        body.addView(modes);
        LinearLayout extras = row();
        Button widget = button("Add widget", "Add a remote widget to your home screen");
        widget.setOnClickListener(v -> { pendingSetup = false; dismissSetup(); addWidget(); });
        extras.addView(widget, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button playback = button("Play / Pause", "Play or pause");
        playback.setEnabled(engine.isPaired());
        playback.setOnClickListener(v -> { clearError(); engine.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        playParams.leftMargin = dp(8);
        extras.addView(playback, playParams);
        body.addView(extras);
        TextView widgetNote = text("The widget stays connected for 1 minute after each tap, with a temporary notification. Hold OK in the app for a long press.", 12, MUTED);
        widgetNote.setPadding(0, dp(10), 0, dp(4));
        body.addView(widgetNote);
        ScrollView setupScroll = new ScrollView(this);
        setupScroll.addView(body);
        setupDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Setup").setView(setupScroll)
                .setPositiveButton("Pair", null)
                .setNegativeButton("Cancel", (dialog, which) -> pendingSetup = false)
                .setNeutralButton("Forget TV", (dialog, which) -> {
                    pendingSetup = false;
                    pendingCode = false;
                    pairingInProgress = false;
                    pairingInterrupted = false;
                    hostDraft = "";
                    codeDraft = "";
                    if (hostInput != null) hostInput.setText("");
                    engine.forget();
                    refreshTarget();
                    enableControls(false);
                })
                .create();
        setupDialog.setOnCancelListener(dialog -> pendingSetup = false);
        setupDialog.setOnDismissListener(dialog -> {
            if (hostInput != null) hostDraft = hostInput.getText().toString();
            setupDialog = null;
            hostInput = null;
        });
        setupDialog.setOnShowListener(dialog -> {
            setupDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(engine.isPaired());
            setupDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String host = hostInput.getText().toString().trim();
                if (host.isEmpty()) {
                    hostInput.setError("Enter your TV’s IP address");
                    return;
                }
                hostDraft = host;
                codeDraft = "";
                pendingSetup = false;
                pairingInterrupted = false;
                pairingInProgress = true;
                refreshSync();
                clearError();
                setupDialog.dismiss();
                engine.startPairing(host);
            });
        });
        setupDialog.show();
    }

    private void showCode() {
        pendingCode = true;
        pendingSetup = false;
        if (!resumed || codeDialog != null) return;
        dismissSetup();
        LinearLayout body = dialogBody();
        body.addView(text("Enter the six-character code shown on your TV.", 15, WHITE));
        codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setFilters(new InputFilter[] {new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        codeInput.setHint("Pairing code");
        codeInput.setTextColor(WHITE);
        codeInput.setHintTextColor(MUTED);
        codeInput.setContentDescription("Six-character TV pairing code");
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(24);
        codeInput.setText(codeDraft);
        body.addView(codeInput, new LinearLayout.LayoutParams(-1, dp(60)));
        codeDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Code from your TV").setView(body)
                .setPositiveButton("Connect", null)
                .setNegativeButton("Cancel", (dialog, which) -> cancelPairing())
                .create();
        codeDialog.setOnCancelListener(dialog -> cancelPairing());
        codeDialog.setOnDismissListener(dialog -> {
            if (codeInput != null) codeDraft = codeInput.getText().toString();
            codeDialog = null;
            codeInput = null;
        });
        codeDialog.setOnShowListener(dialog -> codeDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String code = codeInput.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (!code.matches("[0-9A-F]{6}")) {
                        codeInput.setError("Use the six letters or numbers shown on your TV (0–9, A–F)");
                        return;
                    }
                    codeDraft = code;
                    clearError();
                    codeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    codeInput.setEnabled(false);
                    engine.finishPairing(code);
                }));
        codeDialog.show();
    }

    private void cancelPairing() {
        pendingCode = false;
        pairingInProgress = false;
        pairingInterrupted = false;
        codeDraft = "";
        if (codeInput != null) codeInput.setText("");
        engine.cancelPairing();
        refreshSync();
        stateText.setText(engine.isPaired() ? "TV saved" : "Pair a TV to begin");
    }

    private void addWidget() {
        AppWidgetManager widgets = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, RemoteWidget.class);
        if (widgets.isRequestPinAppWidgetSupported()) {
            try {
                if (widgets.requestPinAppWidget(provider, null, null)) return;
            } catch (RuntimeException exception) {
                // Some launchers advertise pinning but reject the request; the widget picker still works.
            }
        }
        helpDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Add the remote widget")
                .setMessage("Touch and hold an empty area of your phone’s home screen. Choose Widgets, find BareRemote, and drag the remote onto a page. Touch and hold it again to resize it.")
                .setPositiveButton("OK", null).create();
        helpDialog.setOnDismissListener(dialog -> helpDialog = null);
        helpDialog.show();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        engine.setListener(this);
        engine.resume();
        refreshTarget();
        enableControls(engine.isPaired());
        if (pendingCode) showCode();
        else if (pendingSetup) showSetup();
        else getWindow().getDecorView().post(this::requestWidgetNotificationsOnce);
    }

    private void requestWidgetNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || !resumed || isFinishing() || isDestroyed()
                || !engine.isPaired() || pairingInProgress || pendingSetup || pendingCode
                || setupDialog != null || codeDialog != null || helpDialog != null) return;
        SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
        if (ui.getBoolean("widget_notifications_requested", false)) return;
        // Record before opening the system prompt: its pause/resume must not ask again.
        ui.edit().putBoolean("widget_notifications_requested", true).apply();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        // Permission is optional; widget commands remain available after a denial.
    }

    @Override protected void onPause() {
        resumed = false;
        if (pairingInProgress || pendingCode) {
            pairingInterrupted = true;
            pairingInProgress = false;
            pendingCode = false;
            pendingSetup = true;
            codeDraft = "";
            if (codeInput != null) codeInput.setText("");
        }
        dismissSetup();
        dismissCode();
        if (helpDialog != null) helpDialog.dismiss();
        engine.clearListener(this);
        engine.pause();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (hostInput != null) hostDraft = hostInput.getText().toString();
        if (codeInput != null) codeDraft = codeInput.getText().toString();
        state.putString("hostDraft", hostDraft);
        state.putString("codeDraft", codeDraft);
        state.putBoolean("setupOpen", pendingSetup);
        state.putBoolean("codeOpen", pendingCode);
        state.putBoolean("pairingInProgress", pairingInProgress);
        state.putBoolean("pairingInterrupted", pairingInterrupted);
        super.onSaveInstanceState(state);
    }

    @Override public void onState(String state, boolean paired) {
        stateText.setText(state);
        refreshTarget();
        enableControls(paired);
    }

    @Override public void onPairingCodeNeeded() {
        pairingInProgress = true;
        refreshSync();
        showCode();
    }

    @Override public void onPaired() {
        pendingCode = false;
        pendingSetup = false;
        pairingInProgress = false;
        pairingInterrupted = false;
        if (codeInput != null) codeInput.setText("");
        dismissSetup();
        dismissCode();
        codeDraft = "";
        hostDraft = safeHost();
        clearError();
        refreshTarget();
        enableControls(true);
        getWindow().getDecorView().post(this::requestWidgetNotificationsOnce);
    }

    @Override public void onError(String message) {
        pairingInProgress = false;
        refreshSync();
        stateText.setText(engine.isPaired() ? "TV saved" : "Pair your TV in Setup");
        errorText.setText("Error: " + message);
        errorText.setVisibility(View.VISIBLE);
        if (codeInput != null) {
            codeInput.setEnabled(true);
            codeInput.setError(message);
        }
        if (codeDialog != null) {
            Button retry = codeDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            retry.setText("New code");
            retry.setEnabled(true);
            retry.setOnClickListener(v -> {
                pendingCode = false;
                codeDraft = "";
                if (codeInput != null) codeInput.setText("");
                dismissCode();
                clearError();
                pairingInProgress = true;
                refreshSync();
                engine.startPairing(hostDraft);
            });
        }
    }

    private void refreshTarget() {
        String host = safeHost();
        syncButton.setTooltipText(engine.isPaired() && !host.isEmpty()
                ? "Sync: reconnect to " + host : "Pair a TV in Setup");
    }

    private String safeHost() { return engine.host() == null ? "" : engine.host(); }

    private void enableControls(boolean enabled) {
        for (View button : remoteButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : .38f);
        }
        refreshSync();
    }

    private void refreshSync() {
        boolean enabled = engine.isPaired() && !pairingInProgress;
        syncButton.setEnabled(enabled);
        syncButton.setAlpha(enabled ? 1f : .38f);
    }

    private void clearError() { errorText.setVisibility(View.GONE); }

    private void dismissSetup() { if (setupDialog != null) setupDialog.dismiss(); }

    private void dismissCode() { if (codeDialog != null) codeDialog.dismiss(); }

    private LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), 0);
        return body;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private View spacer() {
        View space = new View(this);
        space.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return space;
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setFontFeatureSettings("kern");
        return text;
    }

    private Button button(String value, String description) {
        Button button = new Button(this);
        button.setText(value);
        button.setContentDescription(description);
        button.setTextSize(17);
        button.setTextColor(WHITE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), 0, dp(6), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed}, rectangle(0xff444444, WHITE));
        background.addState(new int[] {android.R.attr.state_focused}, rectangle(0xff222222, WHITE));
        background.addState(new int[0], rectangle(0xff121212, 0xff555555));
        button.setBackground(background);
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable rectangle(int fill, int border) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setStroke(dp(1), border);
        result.setCornerRadius(dp(3));
        return result;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
