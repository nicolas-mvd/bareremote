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
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** A remote, immediately. No content feed, accounts, or background UI work. */
public final class MainActivity extends Activity implements RemoteEngine.Listener {
    private static final int WHITE = 0xffdadcd9;
    private static final int SURFACE = 0xff303234;
    private static final int GREEN = 0xff7bd99a;
    private static final int GREEN_INK = 0xff153d25;
    private static final int MUTED = 0xffaaaaaa;
    private final List<View> remoteButtons = new ArrayList<>();
    private final List<RepeatingKeyTouchListener> repeatingButtons = new ArrayList<>();
    private final List<View> setupActions = new ArrayList<>();
    private RemoteEngine engine;
    private TextView stateText;
    private TextView errorText;
    private ImageButton syncButton;
    private ImageButton pictureButton;
    private ImageButton keyboardButton;
    private ScrollView remoteScroll, keyboardScroll;
    private KeyboardInputView keyboardInput;
    private TextView keyboardStatus;
    private boolean keyboardVisible;
    private Boolean tvKeyboardVisible;
    private long manuallyClosedEditor = -1;
    private long displayedFocus = -1;
    private AlertDialog setupDialog;
    private AlertDialog discoveryDialog;
    private AlertDialog codeDialog;
    private AlertDialog helpDialog;
    private EditText hostInput;
    private EditText codeInput;
    private TvDiscovery discovery;
    private TextView discoverySelection;
    private TextView setupConnectionStatus, setupConnectionError, setupDogStatus;
    private Button setupDogAction, setupDogCheck, setupDogSetup, setupForget;
    private final List<View> setupConnectionActions = new ArrayList<>();
    private boolean resumed;
    private boolean pendingSetup;
    private boolean pendingCode;
    private boolean pairingInProgress;
    private boolean pairingInterrupted;
    private boolean addressReconnectInProgress;
    private int addressReconnectGeneration;
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
        discovery = new TvDiscovery(this);
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
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
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
        remoteScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        RemoteSurface content = new RemoteSurface(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = row();
        ImageButton setup = icon(R.drawable.remote_icon_setup, "Setup");
        setup.setOnClickListener(v -> showSetup());
        header.addView(setup, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout shortcutSpace = row();
        shortcutSpace.setGravity(Gravity.CENTER);
        LinearLayout shortcuts = row();
        shortcuts.setBackground(roundFill(GREEN, 100));
        syncButton = icon(R.drawable.remote_icon_sync, "Sync: reconnect to saved TV");
        syncButton.setImageTintList(ColorStateList.valueOf(GREEN_INK));
        syncButton.setBackground(roundStates(0x00000000, 0x2230523a, 100));
        syncButton.setOnClickListener(v -> { clearError(); engine.reconnect(); });
        shortcuts.addView(syncButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        pictureButton = createModeButton();
        shortcuts.addView(pictureButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        shortcutSpace.addView(shortcuts, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout.LayoutParams shortParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        shortParams.leftMargin = dp(8);
        shortParams.rightMargin = dp(8);
        header.addView(shortcutSpace, shortParams);
        ImageButton power = keyIcon(R.drawable.remote_icon_power, "TV power", KeyEvent.KEYCODE_POWER);
        header.addView(power, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.setHeader(header);

        LinearLayout status = column();
        stateText = text(engine.needsPairingUpgrade() ? RemoteEngine.PAIRING_UPGRADE_MESSAGE
                : engine.isPaired() ? "Connecting…" : "Pair a TV in Setup", 12, MUTED);
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

        keyboardButton = icon(R.drawable.remote_icon_keyboard, "Keyboard mode");
        keyboardButton.setOnClickListener(v -> showKeyboard(true));
        remoteButtons.add(keyboardButton);
        content.setKeyboard(keyboardButton);

        LinearLayout navigation = row();
        ImageButton back = keyIcon(R.drawable.remote_icon_back, "Back", KeyEvent.KEYCODE_BACK);
        ImageButton home = keyIcon(R.drawable.remote_icon_home, "TV home", KeyEvent.KEYCODE_HOME);
        ImageButton input = keyIcon(R.drawable.remote_icon_playstation, "Input / source", KeyEvent.KEYCODE_TV_INPUT);
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
        createKeyboard(screen);
        setContentView(screen);
        screen.requestApplyInsets();
        refreshTarget();
        refreshPictureMode();
        enableControls(engine.isPaired());
    }

    private void createKeyboard(LinearLayout screen) {
        keyboardScroll = new ScrollView(this);
        keyboardScroll.setFillViewport(true);
        keyboardScroll.setVisibility(View.GONE);
        LinearLayout body = column();
        body.setPadding(dp(12), 0, dp(12), dp(20));
        keyboardScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout header = row();
        Button remote = button("‹  Remote", "Return to normal remote mode");
        remote.setBackground(roundStates(0xff1c1e1d, 0xff454b47, 100));
        remote.setOnClickListener(v -> hideKeyboard(true));
        header.addView(remote, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView title = text("Keyboard", 20, WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(header);
        keyboardStatus = text("Select a text field on your TV to start typing.", 15, MUTED);
        keyboardStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        keyboardStatus.setPadding(0, dp(12), 0, dp(12));
        body.addView(keyboardStatus);
        keyboardInput = new KeyboardInputView(this, new KeyboardInputView.Sink() {
            @Override public void commit(String text) { clearError(); engine.sendText(text); }
            @Override public void key(int key) { clearError(); engine.sendKeyboardKey(key); }
        });
        keyboardInput.setTextColor(WHITE);
        keyboardInput.setHintTextColor(MUTED);
        keyboardInput.setTextSize(21);
        keyboardInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        keyboardInput.setBackground(roundFill(0xff1c1e1d, 20));
        body.addView(keyboardInput, new LinearLayout.LayoutParams(-1, dp(80)));
        LinearLayout editing = row();
        editing.setPadding(0, dp(16), 0, dp(12));
        addKeyboardAction(editing, "←", "Move TV text cursor left", KeyEvent.KEYCODE_DPAD_LEFT);
        addKeyboardAction(editing, "→", "Move TV text cursor right", KeyEvent.KEYCODE_DPAD_RIGHT);
        addKeyboardAction(editing, "⌫", "Delete before the TV text cursor", KeyEvent.KEYCODE_DEL);
        addKeyboardAction(editing, "Enter", "Enter on TV", KeyEvent.KEYCODE_ENTER);
        body.addView(editing, new LinearLayout.LayoutParams(-1, dp(76)));
        TextView detail = text("Type or paste to your TV. TV text stays on your TV.", 13, MUTED);
        body.addView(detail);
        screen.addView(keyboardScroll, new LinearLayout.LayoutParams(-1, -1));
    }
    private void addKeyboardAction(LinearLayout row, String label, String description, int key) {
        Button action = button(label, description);
        action.setBackground(roundStates(0xff1c1e1d, 0xff454b47, 100));
        action.setOnClickListener(v -> { keyboardInput.commitComposition(); engine.sendKeyboardKey(key); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(action, params);
    }
    private void showKeyboard(boolean manual) {
        if (!resumed || !engine.isPaired() || pairingInProgress || setupDialog != null) return;
        cancelRepeatingButtons();
        keyboardVisible = true;
        remoteScroll.setVisibility(View.GONE);
        keyboardScroll.setVisibility(View.VISIBLE);
        KeyboardState state = engine.keyboardState();
        displayedFocus = state.focusID;
        updateKeyboardStatus(state);
        keyboardInput.requestFocus();
        keyboardInput.post(() -> {
            if (!resumed || !keyboardVisible) return;
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null) input.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }
    private void hideKeyboard(boolean manual) {
        if (manual) manuallyClosedEditor = engine.keyboardState().imeCounter;
        keyboardVisible = false;
        engine.cancelKeyboardInput();
        if (keyboardInput == null) return;
        keyboardInput.accepting(false);
        keyboardInput.clearPrivateText();
        keyboardInput.clearFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
        keyboardScroll.setVisibility(View.GONE);
        remoteScroll.setVisibility(View.VISIBLE);
    }
    private void updateKeyboardStatus(KeyboardState state) {
        keyboardInput.accepting(keyboardVisible && state.canType());
        keyboardStatus.setText(state.canType() ? "Connected. Type on your TV."
                : state.active ? "Waiting for the TV’s text field…"
                : state.supported ? "Select a text field on your TV to start typing."
                : "Select a TV text field, then tap Sync if needed. Some TV apps do not offer remote text input.");
    }
    @Override public void onKeyboardState(KeyboardState state) {
        if (keyboardInput == null) return;
        boolean hidden = Boolean.FALSE.equals(state.keyboardVisible)
                && !Boolean.FALSE.equals(tvKeyboardVisible);
        tvKeyboardVisible = state.keyboardVisible;
        if (displayedFocus != state.focusID) {
            keyboardInput.accepting(false);
            engine.cancelKeyboardInput();
            displayedFocus = state.focusID;
        }
        if (!state.active) {
            manuallyClosedEditor = -1;
            keyboardInput.accepting(false);
            if (keyboardVisible) hideKeyboard(false);
        } else if (hidden) {
            if (keyboardVisible) hideKeyboard(false);
        } else if (state.showRequested && !keyboardVisible && state.imeCounter != manuallyClosedEditor) showKeyboard(false);
        updateKeyboardStatus(state);
    }
    @Override public void onBackPressed() {
        if (keyboardVisible) { hideKeyboard(true); return; }
        super.onBackPressed();
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
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            button.setContentDescription(description + ". Touch and hold to repeat");
            repeatingButtons.add(new RepeatingKeyTouchListener(button,
                    () -> {
                        BooleanSupplier session = engine.repeatingSession();
                        return (held, completion) -> engine.sendRepeatingKey(key,
                                () -> held.getAsBoolean() && session.getAsBoolean(), completion);
                    }, this::onError));
        }
        remoteButtons.add(button);
        return button;
    }

    private void cancelRepeatingButtons() {
        for (RepeatingKeyTouchListener button : repeatingButtons) button.cancel();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) cancelRepeatingButtons();
    }

    private ImageButton createModeButton() {
        ImageButton button = icon(R.drawable.remote_icon_mode_unknown, "Check or change TV picture mode");
        button.setImageTintList(ColorStateList.valueOf(GREEN_INK));
        button.setBackground(roundStates(0x00000000, 0x2230523a, 100));
        button.setOnClickListener(v -> {
            clearError();
            engine.togglePictureMode(null);
        });
        remoteButtons.add(button);
        return button;
    }

    private void refreshPictureMode() {
        if (pictureButton == null) return;
        String mode = engine.pictureState();
        int icon = "day".equals(mode) ? R.drawable.remote_icon_sun
                : "night".equals(mode) ? R.drawable.remote_icon_moon : R.drawable.remote_icon_mode_unknown;
        String description = "day".equals(mode) ? "Day last confirmed on TV. Tap to toggle TV mode"
                : "night".equals(mode) ? "Night last confirmed on TV. Tap to toggle TV mode"
                : "custom".equals(mode) ? "TV levels differ from its saved preset. Tap to switch preset"
                : "TV mode not confirmed. Sync to check, or tap to switch preset";
        pictureButton.setImageResource(icon);
        pictureButton.setContentDescription(description);
        pictureButton.setTooltipText(description);
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
        hideKeyboard(true);
        pendingSetup = true;
        if (!resumed || setupDialog != null) return;
        pendingCode = false;
        dismissCode();
        LinearLayout body = dialogBody();
        setupActions.clear();
        setupConnectionActions.clear();
        if (engine.needsPairingUpgrade()) {
            TextView upgrade = text(RemoteEngine.PAIRING_UPGRADE_MESSAGE
                    + " Your TV address is saved. Pair using a new TV code on each updated phone.", 15, WHITE);
            upgrade.setPadding(0, 0, 0, dp(12));
            body.addView(upgrade);
        }
        if (pairingInterrupted) {
            TextView interrupted = text("Pairing stopped when the app left the screen. Pair again to request a new code.", 14, WHITE);
            interrupted.setPadding(0, 0, 0, dp(12));
            body.addView(interrupted);
        }

        LinearLayout television = settingsSection(body, "Television");
        TextView saved = text(engine.hasSavedTv() ? (engine.isPaired() ? "Paired TV · " : "Saved TV · ") + safeHost() : "Add your TV", 17, WHITE);
        saved.setTypeface(null, Typeface.BOLD);
        television.addView(saved);
        addSettingsNote(television, "Keep your phone and TV on the same network.");
        setupConnectionStatus = text(stateText.getText().toString(), 14, MUTED);
        setupConnectionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setupConnectionStatus.setPadding(0, dp(10), 0, 0);
        television.addView(setupConnectionStatus);
        setupConnectionError = text(errorText.getText().toString(), 14, 0xffffb36b);
        setupConnectionError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setupConnectionError.setVisibility(errorText.getVisibility());
        television.addView(setupConnectionError);
        LinearLayout connectionButtons = row();
        Button scan = button("Scan for TVs", "Scan the local network for Android TVs");
        scan.setOnClickListener(v -> showDiscovery());
        connectionButtons.addView(scan, new LinearLayout.LayoutParams(0, dp(48), 1));
        setupActions.add(scan);
        setupConnectionActions.add(scan);
        if (engine.isPaired()) {
            Button reconnect = button("Reconnect", "Reconnect to saved TV and find its new address if needed");
            reconnect.setOnClickListener(v -> { clearError(); engine.reconnect(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.leftMargin = dp(8);
            connectionButtons.addView(reconnect, params);
            setupActions.add(reconnect);
            setupConnectionActions.add(reconnect);
        }
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2);
        connectionParams.topMargin = dp(12);
        television.addView(connectionButtons, connectionParams);
        if (engine.isPaired()) addSettingsNote(television,
                "Reconnect looks for your paired TV automatically if its IP address changes.");
        discoverySelection = text("", 13, GREEN);
        discoverySelection.setVisibility(View.GONE);
        discoverySelection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        television.addView(discoverySelection);

        LinearLayout connectionOptions = column();
        connectionOptions.setPadding(0, dp(8), 0, 0);
        connectionOptions.addView(text("TV IP address", 13, MUTED));
        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setHint("192.168.1.10");
        hostInput.setTextColor(WHITE);
        hostInput.setHintTextColor(MUTED);
        hostInput.setContentDescription("TV IPv4 address");
        hostInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(253)});
        hostInput.setText(hostDraft.isEmpty() ? safeHost() : hostDraft);
        hostInput.setSelectAllOnFocus(true);
        connectionOptions.addView(hostInput, new LinearLayout.LayoutParams(-1, dp(56)));
        setupActions.add(hostInput);
        setupConnectionActions.add(hostInput);
        Button addressAction = button(engine.isPaired() ? "Use this address" : "Pair with TV",
                engine.isPaired() ? "Check saved TV identity at this address" : "Request a pairing code on the TV");
        addressAction.setOnClickListener(v -> {
            if (engine.isPaired()) reconnectSetupAddress();
            else pairSetupAddress();
        });
        connectionOptions.addView(addressAction, new LinearLayout.LayoutParams(-1, dp(48)));
        setupActions.add(addressAction);
        setupConnectionActions.add(addressAction);
        if (engine.isPaired()) {
            addSettingsNote(connectionOptions, "Use this address checks your saved TV’s identity. A different or reset TV needs a new code.");
            Button pairCode = button("Pair with a new TV code", "Pair a different or reset TV using its TV code");
            pairCode.setOnClickListener(v -> pairSetupAddress());
            addSettingsAction(connectionOptions, pairCode);
            setupConnectionActions.add(pairCode);
            addSettingsDisclosure(television, "Change address or pair a TV", connectionOptions);
        } else {
            addSettingsNote(connectionOptions, "Select your TV after scanning, or enter its address. It will show a six-character code. Each phone pairs separately.");
            television.addView(connectionOptions);
        }

        LinearLayout dog = settingsSection(body, "Dog Mode");
        TextView channel = text("NS NOW · CANAL 10", 17, WHITE);
        channel.setTypeface(null, Typeface.BOLD);
        dog.addView(channel);
        addSettingsNote(dog, "Keeps the TV on while you’re out. Ending Dog Mode restores its previous energy settings and opens Home.");
        addSettingsNote(dog, "One-time setup in BareHome on the TV is required. NS NOW requires USB debugging to be off.");
        setupDogStatus = text("", 14, MUTED);
        setupDogStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setupDogStatus.setPadding(0, dp(12), 0, dp(8));
        dog.addView(setupDogStatus);
        setupDogAction = button("Start Dog Mode", "Start Dog Mode on the TV");
        setupDogAction.setOnClickListener(v -> {
            if ("off".equals(engine.dogMode())) engine.startDogMode();
            else if ("active".equals(engine.dogMode()) || "starting".equals(engine.dogMode()) || "restore_required".equals(engine.dogMode())
                    || "unknown".equals(engine.dogMode()) || "error".equals(engine.dogMode())
                    || DogStateWire.isStartupFailure(engine.dogMode())) engine.stopDogMode();
        });
        dog.addView(setupDogAction, new LinearLayout.LayoutParams(-1, -2));
        setupDogCheck = button("Check status", "Ask the TV for its current Dog Mode state");
        setupDogCheck.setOnClickListener(v -> engine.refreshDogMode());
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(48));
        checkParams.topMargin = dp(8);
        dog.addView(setupDogCheck, checkParams);
        setupDogSetup = button("Set up on TV", "Open the Dog Mode setup prompt on the TV");
        setupDogSetup.setOnClickListener(v -> engine.openDogSetup());
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(-1, dp(48));
        setupParams.topMargin = dp(8);
        dog.addView(setupDogSetup, setupParams);

        LinearLayout controls = settingsSection(body, "Remote & widgets");
        TextView pictureLabel = text("Day / Night and inputs", 15, WHITE);
        pictureLabel.setTypeface(null, Typeface.BOLD);
        controls.addView(pictureLabel);
        addSettingsNote(controls, "The sun or moon shows the last TV-confirmed picture preset. A question mark means it is unconfirmed. Sync checks again. Requires BareHome picture controls. The PlayStation button opens the TV’s source menu.");
        Button widget = button("Add widget", "Add a remote widget to your home screen");
        widget.setOnClickListener(v -> { pendingSetup = false; dismissSetup(); addWidget(); });
        addSettingsAction(controls, widget);
        Button playback = button("Play / Pause", "Play or pause TV playback");
        playback.setEnabled(engine.isPaired());
        playback.setOnClickListener(v -> { clearError(); engine.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); });
        addSettingsAction(controls, playback);
        addSettingsNote(controls, "The widget connects for 1 minute after each tap. Use the app to hold directions, volume or OK.");

        LinearLayout app = settingsSection(body, "App updates");
        app.addView(text(installedVersion(), 13, MUTED));
        Button updates = button("Check for updates", "Check for a BareRemote update");
        updates.setOnClickListener(v -> { pendingSetup = false; dismissSetup(); AppUpdates.open(this); });
        addSettingsAction(app, updates);

        LinearLayout advanced = column();
        advanced.setPadding(0, dp(8), 0, dp(12));
        addSettingsNote(advanced, "Forgetting removes only this phone’s saved pairing. Other remotes are unaffected.");
        if (engine.hasSavedTv()) {
            setupForget = button("Forget TV…", "Remove this phone’s saved TV pairing");
            setupForget.setOnClickListener(v -> confirmForgetTv());
            addSettingsAction(advanced, setupForget);
        }
        addSettingsDisclosure(body, "Advanced", advanced);

        ScrollView setupScroll = new ScrollView(this);
        setupScroll.addView(body);
        setupDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Settings").setView(setupScroll)
                .setPositiveButton("Done", (dialog, which) -> pendingSetup = false)
                .create();
        setupDialog.setOnCancelListener(dialog -> pendingSetup = false);
        setupDialog.setOnDismissListener(dialog -> {
            cancelAddressReconnect();
            dismissDiscovery();
            if (hostInput != null) hostDraft = hostInput.getText().toString();
            setupDialog = null;
            hostInput = null;
            discoverySelection = null;
            setupConnectionStatus = null;
            setupConnectionError = null;
            setupDogStatus = null;
            setupDogAction = null;
            setupDogCheck = null;
            setupDogSetup = null;
            setupForget = null;
            setupActions.clear();
            setupConnectionActions.clear();
        });
        setupDialog.show();
        refreshSetupDogMode();
        if (engine.isPaired()) engine.refreshDogMode();
    }

    private LinearLayout settingsSection(LinearLayout body, String title) {
        TextView heading = text(title, 16, WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        heading.setPadding(0, dp(20), 0, dp(10));
        body.addView(heading);
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundFill(0xff252728, 12));
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void addSettingsNote(LinearLayout body, String value) {
        TextView note = text(value, 13, MUTED);
        note.setPadding(0, dp(8), 0, dp(4));
        body.addView(note);
    }

    private void addSettingsAction(LinearLayout body, Button action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        body.addView(action, params);
        setupActions.add(action);
    }

    private void addSettingsDisclosure(LinearLayout body, String title, LinearLayout content) {
        Button disclosure = button(title + "  ▾", title);
        content.setVisibility(View.GONE);
        disclosure.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            disclosure.setText(title + (open ? "  ▴" : "  ▾"));
        });
        addSettingsAction(body, disclosure);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));
    }

    private void confirmForgetTv() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Forget this TV on this phone?")
                .setMessage("You will need a new TV code to pair this phone again. Other remotes keep their pairing.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget TV", (dialog, which) -> {
                    if (engine.dogBusy() || "active".equals(engine.dogMode()) || "starting".equals(engine.dogMode()) || "restore_required".equals(engine.dogMode())) return;
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
                    dismissSetup();
                }).show();
    }

    private void refreshSetupDogMode() {
        if (setupDogStatus == null) return;
        String mode = engine.dogMode();
        boolean busy = engine.dogBusy();
        boolean paired = engine.isPaired();
        setupDogStatus.setText(paired ? engine.dogStatus() : "Pair your TV above to use Dog Mode.");
        setupDogStatus.setTextColor("active".equals(mode) ? GREEN
                : "restore_required".equals(mode) || "error".equals(mode) || DogStateWire.isStartupFailure(mode) ? 0xffffb36b : MUTED);
        boolean actionable = "off".equals(mode) || "active".equals(mode) || "starting".equals(mode) || "restore_required".equals(mode)
                || "unknown".equals(mode) || "error".equals(mode) || DogStateWire.isStartupFailure(mode);
        setupDogAction.setVisibility(actionable ? View.VISIBLE : View.GONE);
        setupDogAction.setText("off".equals(mode) ? "Start Dog Mode"
                : "active".equals(mode) ? "End Dog Mode" : "Restore settings & exit");
        setupDogAction.setContentDescription(setupDogAction.getText());
        setupDogAction.setEnabled(paired && !busy && !addressReconnectInProgress);
        setupDogCheck.setEnabled(paired && !busy && !addressReconnectInProgress);
        setupDogCheck.setText("Check status");
        setupDogSetup.setVisibility(paired ? View.VISIBLE : View.GONE);
        setupDogSetup.setEnabled(paired && !busy && !addressReconnectInProgress);
        for (View action : setupConnectionActions) action.setEnabled(!busy && !addressReconnectInProgress);
        if (setupForget != null) setupForget.setEnabled(!busy && !addressReconnectInProgress
                && !"active".equals(mode) && !"starting".equals(mode) && !"restore_required".equals(mode));
    }

    @Override public void onDogMode() { refreshSetupDogMode(); }

    private String setupAddress() {
        if (hostInput == null || addressReconnectInProgress) return null;
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            hostInput.setError("Enter your TV’s IP address");
            return null;
        }
        hostDraft = host;
        return host;
    }

    private void pairSetupAddress() {
        String host = setupAddress();
        if (host == null) return;
        codeDraft = "";
        pendingSetup = false;
        pairingInterrupted = false;
        pairingInProgress = true;
        refreshSync();
        clearError();
        setupDialog.dismiss();
        engine.startPairing(host);
    }

    private void reconnectSetupAddress() {
        String host = setupAddress();
        if (host == null) return;
        cancelRepeatingButtons();
        clearError();
        final AlertDialog dialog = setupDialog;
        final int token = ++addressReconnectGeneration;
        addressReconnectInProgress = true;
        setSetupBusy(true);
        discoverySelection.setTextColor(GREEN);
        discoverySelection.setText("Checking your saved TV at " + host + "…");
        discoverySelection.setPadding(0, dp(8), 0, 0);
        discoverySelection.setVisibility(View.VISIBLE);
        enableControls(false);
        engine.reconnectTo(host, error -> {
            if (token != addressReconnectGeneration || !resumed || setupDialog != dialog) return;
            addressReconnectInProgress = false;
            setSetupBusy(false);
            enableControls(engine.isPaired());
            refreshTarget();
            if (error != null) {
                discoverySelection.setTextColor(WHITE);
                discoverySelection.setText(error);
                return;
            }
            hostDraft = safeHost();
            pendingSetup = false;
            dialog.dismiss();
        });
    }

    private void setSetupBusy(boolean busy) {
        for (View action : setupActions) action.setEnabled(!busy);
        refreshSetupDogMode();
    }

    private void cancelAddressReconnect() {
        if (!addressReconnectInProgress) return;
        addressReconnectGeneration++;
        addressReconnectInProgress = false;
        engine.cancelReconnectTo();
        enableControls(engine.isPaired());
    }

    private void showDiscovery() {
        if (!resumed || setupDialog == null || discoveryDialog != null) return;
        LinearLayout body = dialogBody();
        body.addView(text(engine.isPaired()
                ? "Select your saved TV to reconnect without a pairing code. Its identity will be checked before its address is saved."
                : "Turn on your TV. Select it below, then tap Pair in Setup to request its code.", 15, WHITE));
        TextView status = text("Scanning…", 14, MUTED);
        status.setPadding(0, dp(12), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        LinearLayout results = column();
        body.addView(results);
        TextView fallback = text("If your TV is missing, check its network connection or enter its IP address in Setup.", 13, MUTED);
        fallback.setPadding(0, dp(12), 0, dp(8));
        body.addView(fallback);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("TVs on your network").setView(scroll)
                .setPositiveButton("Stop", null)
                .setNegativeButton("Back", null).create();
        discoveryDialog = dialog;
        TvDiscovery.Listener listener = (devices, scanning, message) -> {
            if (!resumed || discoveryDialog != dialog) return;
            status.setText(message);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(scanning ? "Stop" : "Scan again");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTag(scanning);
            results.removeAllViews();
            for (TvDiscovery.Device device : devices) {
                Button select = button(device.name + "\n" + device.host,
                        "Select " + device.name + ", " + device.host);
                select.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                select.setPadding(dp(12), dp(8), dp(12), dp(8));
                select.setTextSize(15);
                select.setMinimumHeight(dp(64));
                select.setOnClickListener(v -> {
                    if (!resumed || discoveryDialog != dialog || hostInput == null
                            || addressReconnectInProgress) return;
                    hostInput.setText(device.host);
                    hostInput.setError(null);
                    hostDraft = device.host;
                    discoverySelection.setTextColor(GREEN);
                    discoverySelection.setText("Selected " + device.name + ". Tap Pair to continue.");
                    discoverySelection.setPadding(0, dp(8), 0, 0);
                    discoverySelection.setVisibility(View.VISIBLE);
                    dialog.dismiss();
                    if (engine.isPaired()) reconnectSetupAddress();
                });
                LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
                row.bottomMargin = dp(8);
                results.addView(select, row);
            }
        };
        dialog.setOnDismissListener(ignored -> {
            discovery.close();
            if (discoveryDialog == dialog) discoveryDialog = null;
        });
        dialog.setOnShowListener(ignored -> {
            Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            action.setOnClickListener(v -> {
                if (Boolean.TRUE.equals(action.getTag())) {
                    discovery.close();
                    // Keep the found rows available for selection after stopping.
                    action.setTag(false);
                    action.setText("Scan again");
                    status.setText(results.getChildCount() == 0
                            ? "Scan stopped. No TVs found." : "Scan stopped. Select your TV.");
                } else discovery.start(listener);
            });
            discovery.start(listener);
        });
        dialog.show();
    }

    private void dismissDiscovery() {
        discovery.close();
        if (discoveryDialog != null) discoveryDialog.dismiss();
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
        stateText.setText(engine.needsPairingUpgrade() ? RemoteEngine.PAIRING_UPGRADE_MESSAGE
                : engine.isPaired() ? "TV saved" : "Pair a TV to begin");
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
        refreshPictureMode();
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
        cancelRepeatingButtons();
        hideKeyboard(false);
        manuallyClosedEditor = -1;
        cancelAddressReconnect();
        if (pairingInProgress || pendingCode) {
            pairingInterrupted = true;
            pairingInProgress = false;
            pendingCode = false;
            pendingSetup = true;
            codeDraft = "";
            if (codeInput != null) codeInput.setText("");
        }
        dismissDiscovery();
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
        if (setupConnectionStatus != null) setupConnectionStatus.setText(state);
        if ("Connected".equals(state) || "Connecting…".equals(state)
                || "Looking for your TV…".equals(state) || "Checking saved TV…".equals(state)
                || "Requesting TV code…".equals(state) || "Pairing…".equals(state)) clearError();
        refreshTarget();
        refreshPictureMode();
        enableControls(paired);
    }

    @Override public void onPictureState(String state) { refreshPictureMode(); }

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
        cancelRepeatingButtons();
        if (keyboardVisible) keyboardStatus.setText(message);
        pairingInProgress = false;
        refreshSync();
        stateText.setText(engine.needsPairingUpgrade() ? RemoteEngine.PAIRING_UPGRADE_MESSAGE
                : engine.isPaired() ? "Disconnected" : "Pair your TV in Setup");
        if (setupConnectionStatus != null) setupConnectionStatus.setText(stateText.getText());
        errorText.setText("Error: " + message);
        errorText.setVisibility(View.VISIBLE);
        if (setupConnectionError != null) {
            setupConnectionError.setText(message);
            setupConnectionError.setVisibility(View.VISIBLE);
        }
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
        syncButton.setTooltipText(engine.needsPairingUpgrade() ? RemoteEngine.PAIRING_UPGRADE_MESSAGE
                : engine.isPaired() && !host.isEmpty() ? "Sync: reconnect to " + host : "Pair a TV in Setup");
    }

    private String safeHost() { return engine.host() == null ? "" : engine.host(); }

    private String installedVersion() {
        try {
            return "BareRemote " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "BareRemote";
        }
    }

    private void enableControls(boolean enabled) {
        enabled = enabled && !addressReconnectInProgress && !engine.isReconnectingTo();
        if (!enabled) cancelRepeatingButtons();
        for (View button : remoteButtons) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : .38f);
        }
        refreshSync();
    }

    private void refreshSync() {
        boolean enabled = engine.isPaired() && !pairingInProgress
                && !addressReconnectInProgress && !engine.isReconnectingTo();
        syncButton.setEnabled(enabled);
        syncButton.setAlpha(enabled ? 1f : .38f);
    }

    private void clearError() {
        errorText.setVisibility(View.GONE);
        if (setupConnectionError != null) setupConnectionError.setVisibility(View.GONE);
    }

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
