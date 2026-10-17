package uy.nico.bareremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A remote, immediately. No content feed, accounts, or background UI work. */
public final class MainActivity extends Activity implements RemoteEngine.Listener {
    private static final int WHITE = 0xffeeeeee;
    private static final int MUTED = 0xffaaaaaa;
    private final List<Button> remoteButtons = new ArrayList<>();
    private RemoteEngine engine;
    private TextView targetText;
    private TextView stateText;
    private TextView errorText;
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
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
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
        screen.setBackgroundColor(Color.BLACK);
        screen.setPadding(dp(16), dp(12), dp(16), dp(12));
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
            view.setPadding(dp(16) + left, dp(12) + top, dp(16) + right, dp(12) + bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout content = column();
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout titleRow = row();
        LinearLayout title = column();
        TextView name = text("BareRemote", 22, WHITE);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.addView(name);
        targetText = text("", 13, MUTED);
        targetText.setSingleLine(true);
        targetText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.addView(targetText);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button setup = button("Setup", "Set up or change TV");
        setup.setOnClickListener(v -> showSetup());
        titleRow.addView(setup, new LinearLayout.LayoutParams(dp(86), dp(48)));
        content.addView(titleRow);
        stateText = text(engine.isPaired() ? "Connecting…" : "Pair a TV to begin", 14, MUTED);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
        stateParams.topMargin = dp(10);
        stateParams.bottomMargin = dp(6);
        content.addView(stateText, stateParams);
        errorText = text("", 14, WHITE);
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        errorText.setVisibility(View.GONE);
        content.addView(errorText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pad = column();
        pad.setPadding(0, dp(10), 0, dp(10));
        LinearLayout top = row();
        top.addView(spacer(), new LinearLayout.LayoutParams(0, dp(64), 1));
        addControl(top, "↑", "Up", KeyEvent.KEYCODE_DPAD_UP, 64);
        top.addView(spacer(), new LinearLayout.LayoutParams(0, dp(64), 1));
        pad.addView(top);
        LinearLayout middle = row();
        addControl(middle, "←", "Left", KeyEvent.KEYCODE_DPAD_LEFT, 64);
        Button okay = addControl(middle, "OK", "OK. Touch and hold for a long press",
                KeyEvent.KEYCODE_DPAD_CENTER, 64);
        okay.setOnLongClickListener(v -> {
            clearError();
            engine.sendLongKey(KeyEvent.KEYCODE_DPAD_CENTER);
            return true;
        });
        addControl(middle, "→", "Right", KeyEvent.KEYCODE_DPAD_RIGHT, 64);
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(-1, -2);
        middleParams.topMargin = dp(8);
        middleParams.bottomMargin = dp(8);
        pad.addView(middle, middleParams);
        LinearLayout bottom = row();
        bottom.addView(spacer(), new LinearLayout.LayoutParams(0, dp(64), 1));
        addControl(bottom, "↓", "Down", KeyEvent.KEYCODE_DPAD_DOWN, 64);
        bottom.addView(spacer(), new LinearLayout.LayoutParams(0, dp(64), 1));
        pad.addView(bottom);
        content.addView(pad);

        LinearLayout navigation = row();
        addControl(navigation, "Back", "Back", KeyEvent.KEYCODE_BACK, 52);
        addControl(navigation, "Home", "TV home", KeyEvent.KEYCODE_HOME, 52);
        addRow(content, navigation);
        LinearLayout volume = row();
        addControl(volume, "Vol −", "Volume down", KeyEvent.KEYCODE_VOLUME_DOWN, 52);
        addControl(volume, "Mute", "Mute or unmute", KeyEvent.KEYCODE_VOLUME_MUTE, 52);
        addControl(volume, "Vol +", "Volume up", KeyEvent.KEYCODE_VOLUME_UP, 52);
        addRow(content, volume);
        LinearLayout playback = row();
        addControl(playback, "Play / Pause", "Play or pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 52);
        addControl(playback, "Power", "TV power", KeyEvent.KEYCODE_POWER, 52);
        addRow(content, playback);

        Button addWidget = button("Add widget", "Add a remote widget to your home screen");
        addWidget.setOnClickListener(v -> addWidget());
        LinearLayout.LayoutParams widgetParams = new LinearLayout.LayoutParams(-1, dp(48));
        widgetParams.topMargin = dp(16);
        content.addView(addWidget, widgetParams);
        TextView widgetHint = text("Give the widget a home-screen page. Touch and hold it, then drag the edges to resize.", 12, MUTED);
        widgetHint.setPadding(0, dp(8), 0, dp(8));
        content.addView(widgetHint);
        content.addView(text("Prototype · Local Wi-Fi", 11, MUTED));
        setContentView(screen);
        screen.requestApplyInsets();
        refreshTarget();
        enableControls(engine.isPaired());
    }

    private Button addControl(LinearLayout row, String label, String description, int key, int height) {
        Button control = button(label, description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), 1);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        row.addView(control, params);
        control.setOnClickListener(v -> {
            clearError();
            engine.sendKey(key);
        });
        remoteButtons.add(control);
        return control;
    }

    private void addRow(LinearLayout content, LinearLayout row) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        content.addView(row, params);
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
        setupDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pair your TV").setView(body)
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
    }

    @Override public void onError(String message) {
        pairingInProgress = false;
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
                engine.startPairing(hostDraft);
            });
        }
    }

    private void refreshTarget() {
        String host = safeHost();
        targetText.setText(engine.isPaired() && !host.isEmpty() ? host : "No TV paired");
    }

    private String safeHost() { return engine.host() == null ? "" : engine.host(); }

    private void enableControls(boolean enabled) {
        for (Button button : remoteButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : .38f);
        }
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
