package uy.nico.bareremote;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.util.ArrayList;
import java.util.List;

/** Test-only APK exercises Android's actual EditText/InputConnection, without a TV. */
public final class KeyboardInstrumentation extends Instrumentation {
    private int assertions;
    private boolean integration;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); integration = arguments != null && arguments.getString("integration", "false").equals("true"); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { exercise(); } catch (Throwable problem) { failure[0] = problem; }
        });
        if (failure[0] == null && integration) {
            try { exerciseApp(); } catch (Throwable problem) { failure[0] = problem; }
        }
        if (failure[0] != null) {
            result.putString("stream", "FAIL: " + failure[0].getClass().getSimpleName() + ": " + failure[0].getMessage());
            finish(0, result);
        } else {
            result.putString("stream", "Native keyboard: " + assertions + " assertions passed\n");
            finish(-1, result);
        }
    }
    private void exercise() {
        List<String> text = new ArrayList<>(); List<Integer> keys = new ArrayList<>();
        KeyboardInputView view = new KeyboardInputView(getTargetContext(), new KeyboardInputView.Sink() {
            public void commit(String value) { text.add(value); }
            public void key(int value) { keys.add(value); }
        });
        view.accepting(true);
        EditorInfo info = new EditorInfo();
        InputConnection input = view.onCreateInputConnection(info);
        check(input != null, "native input connection exists");
        check((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "keyboard personalization disabled");
        check(!view.isSaveEnabled(), "typed composition not saved in activity state");
        input.setComposingText("に", 1); input.setComposingText("日本", 1);
        check(text.isEmpty() && "日本".contentEquals(view.getText()), "composition remains local");
        input.commitText("日本", 1);
        check(text.size() == 1 && "日本".equals(text.get(0)) && view.length() == 0, "native composed text commits exactly once and clears");
        input.finishComposingText();
        check(text.size() == 1, "finish after commit does not duplicate");
        input.setComposingText("漢字", 1); input.finishComposingText(); input.finishComposingText();
        check(text.size() == 2 && "漢字".equals(text.get(1)), "composition finish sends once");
        input.commitText("é😀漢", 1);
        check(text.size() == 3 && "é😀漢".equals(text.get(2)), "native Unicode/paste-sized commit preserved");
        input.setComposingText("ab", 1); input.deleteSurroundingText(1, 0);
        check(keys.isEmpty() && "a".contentEquals(view.getText()), "local composition deletion does not delete TV text");
        input.finishComposingText();
        check(text.size() == 4 && "a".equals(text.get(3)), "edited composition commits final text only");
        input.deleteSurroundingText(2, 1);
        check(keys.size() == 3 && keys.get(0) == 67 && keys.get(1) == 67 && keys.get(2) == 112, "empty pad forwards bounded delete keys");
        int before = keys.size();
        check(!input.deleteSurroundingText(33, 0) && keys.size() == before, "IME deletion request bounded");
        input.performEditorAction(EditorInfo.IME_ACTION_DONE);
        check(keys.get(keys.size() - 1) == 66, "native Done sends TV Enter");
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
        check(keys.get(keys.size() - 1) == 21 && keys.size() == before + 2, "caret key down delivered once");
        input.setComposingText("private", 1); view.accepting(false);
        check(view.length() == 0, "hide/focus loss discards pending composition");
        input.commitText("late", 1); input.finishComposingText(); input.performEditorAction(EditorInfo.IME_ACTION_DONE);
        check(text.size() == 4 && keys.size() == before + 2, "late native callbacks cannot send after mode exit");
        view.accepting(true); input.commitText("next field", 1);
        check(text.size() == 5 && "next field".equals(text.get(4)), "new focus can type without old composition");
        int keyCount = keys.size(); input.commitText("\n", 1);
        check(keys.size() == keyCount + 1 && keys.get(keys.size() - 1) == 66 && text.size() == 5,
                "native keyboard newline Return submits with TV Enter");
    }
    private void exerciseApp() throws Exception {
        android.app.Activity activity = startActivitySync(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClassName("uy.nico.bareremote", "uy.nico.bareremote.MainActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        RemoteEngine engine = RemoteEngine.get(getTargetContext());
        waitFor(() -> engine.keyboardState().supported, "paired loopback session ready");
        check(!engine.keyboardState().active, "connection alone does not open keyboard");
        click(activity, "Keyboard mode");
        check(visible(activity), "manual keyboard button opens keyboard without TV field");
        runOnMainSync(() -> editor(activity).onCreateInputConnection(new EditorInfo()).commitText("blocked before field", 1));
        check(!engine.keyboardState().canType() && editor(activity).length() == 0, "manual fallback cannot buffer or send before focus");
        click(activity, "Return to normal remote mode");
        check(!visible(activity), "explicit Remote button restores normal remote");
        click(activity, "OK. Touch and hold for a long press");
        waitFor(() -> visible(activity) && engine.keyboardState().canType(), "TV SHOW automatically switches to keyboard");
        long firstFocus = engine.keyboardState().focusID;
        runOnMainSync(() -> {
            KeyboardInputView view = editor(activity);
            InputConnection input = view.onCreateInputConnection(new EditorInfo());
            input.setComposingText("日本", 1); input.commitText("日本", 1); input.finishComposingText();
            input.commitText("é😀漢", 1);
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test fixture", "paste 😀"));
            check(view.onTextContextMenuItem(android.R.id.paste), "native context-menu paste accepted in foreground");
            check(view.length() == 0, "no typed text retained after actual commits/paste");
        });
        click(activity, "Delete before the TV text cursor"); click(activity, "Enter on TV");
        networkIdle(engine);
        check(engine.keyboardState().canType(), "Unicode/paste/edit keys leave paired session available");
        click(activity, "Return to normal remote mode");
        click(activity, "OK. Touch and hold for a long press");
        android.os.SystemClock.sleep(400); waitForIdleSync();
        check(!visible(activity) && engine.keyboardState().focusID == firstFocus,
                "repeated TV SHOW for same field honors manual remote override");
        click(activity, "OK. Touch and hold for a long press");
        waitFor(() -> visible(activity) && engine.keyboardState().focusID != firstFocus, "new TV focus returns to keyboard automatically");
        runOnMainSync(() -> editor(activity).onCreateInputConnection(new EditorInfo()).setComposingText("discard on focus loss", 1));
        runOnMainSync(() -> engine.sendKey(4));
        waitFor(() -> !visible(activity), "TV focus loss returns to remote");
        check(editor(activity).length() == 0, "TV focus loss clears private composition");
        // Native hold-to-repeat remains active on the normal remote.
        android.view.View up = find(activity.getWindow().getDecorView(), "Up. Touch and hold to repeat");
        runOnMainSync(() -> up.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, up.getWidth()/2f, up.getHeight()/2f, 0)));
        android.os.SystemClock.sleep(700);
        runOnMainSync(() -> up.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 700, android.view.MotionEvent.ACTION_UP, up.getWidth()/2f, up.getHeight()/2f, 0)));
        networkIdle(engine);
        check(engine.keyboardState().supported, "normal hold controls retain connected session");
    }
    private void networkIdle(RemoteEngine engine) throws Exception {
        java.lang.reflect.Field field = RemoteEngine.class.getDeclaredField("worker"); field.setAccessible(true);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ((java.util.concurrent.ThreadPoolExecutor) field.get(engine)).execute(done::countDown);
        check(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "foreground network queue finishes");
        waitForIdleSync();
    }
    private void click(android.app.Activity activity, String description) {
        runOnMainSync(() -> {
            android.view.View view = find(activity.getWindow().getDecorView(), description);
            check(view != null && view.isShown() && view.isEnabled(), "visible control: " + description);
            check(view.performClick(), "control click: " + description);
        });
        waitForIdleSync();
    }
    private android.view.View find(android.view.View view, String description) {
        if (description.equals(String.valueOf(view.getContentDescription()))) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i=0;i<group.getChildCount();i++) {
                android.view.View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
    private boolean visible(android.app.Activity activity) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("keyboardVisible"); field.setAccessible(true); return field.getBoolean(activity); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private KeyboardInputView editor(android.app.Activity activity) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("keyboardInput"); field.setAccessible(true); return (KeyboardInputView) field.get(activity); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void waitFor(java.util.function.BooleanSupplier condition, String message) {
        long until = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(25);
        check(condition.getAsBoolean(), message);
    }

    private void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
}
