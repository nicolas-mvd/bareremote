package uy.nico.bareremote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/** A native, transient IME pad: compose locally, send committed Unicode chunks once. */
final class KeyboardInputView extends EditText {
    interface Sink { void commit(String text); void key(int code); }
    private final Sink sink;
    private boolean accepting;

    KeyboardInputView(Context context, Sink sink) {
        super(context);
        this.sink = sink;
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_ACTION_DONE);
        setSaveEnabled(false);
        setFreezesText(false);
        setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setSelectAllOnFocus(false);
        setTextIsSelectable(false);
        setContentDescription("TV keyboard. Type or paste to send to the TV");
        setHint("Type to send to TV");
        setMinLines(1);
        setMaxLines(4);
    }

    void accepting(boolean value) {
        accepting = value;
        setEnabled(true);
        setAlpha(value ? 1f : .65f);
        if (!value) clearPrivateText();
    }
    void clearPrivateText() {
        Editable editable = getText();
        if (editable != null) { BaseInputConnection.removeComposingSpans(editable); editable.clear(); }
    }
    private boolean commitChunk(CharSequence value) {
        if (!accepting) { clearPrivateText(); return false; }
        String text = value == null ? "" : value.toString();
        clearPrivateText();
        if (!text.isEmpty()) sink.commit(text);
        return true;
    }
    void commitComposition() { finishLocalComposition(); }
    private void finishLocalComposition() {
        Editable text = getText();
        if (text != null && text.length() != 0) commitChunk(text.toString());
    }
    private boolean editKey(int key) {
        if (!accepting) return false;
        finishLocalComposition();
        sink.key(key);
        return true;
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection base = super.onCreateInputConnection(info);
        if (base == null) return null;
        info.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        info.initialSelStart = info.initialSelEnd = 0;
        return new InputConnectionWrapper(base, false) {
            @Override public boolean commitText(CharSequence text, int cursor) {
                // Some native keyboards deliver their Return key as a newline commit.
                // Paste remains a literal Unicode chunk through the explicit paste action below.
                if (text != null && ("\n".contentEquals(text) || "\r".contentEquals(text) || "\r\n".contentEquals(text)))
                    return editKey(KeyEvent.KEYCODE_ENTER);
                // setComposingText remains entirely local; this final native commit is sent once.
                return commitChunk(text);
            }
            @Override public boolean setComposingText(CharSequence text, int cursor) {
                if (!accepting) return false;
                if (text != null && text.length() > KeyboardSession.MAX_TEXT) return false;
                return super.setComposingText(text, cursor);
            }
            @Override public boolean finishComposingText() {
                if (accepting) finishLocalComposition(); else clearPrivateText();
                return super.finishComposingText();
            }
            @Override public boolean commitCompletion(CompletionInfo completion) {
                return completion != null && commitChunk(completion.getText());
            }
            @Override public boolean commitCorrection(CorrectionInfo correction) {
                // Previously committed TV text is not a local editable buffer.
                return false;
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (!accepting || before < 0 || after < 0 || before > 32 || after > 32) return false;
                if (getText().length() != 0) return deleteLocal(before, after, false);
                for (int i = 0; i < before; i++) sink.key(KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < after; i++) sink.key(KeyEvent.KEYCODE_FORWARD_DEL);
                return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                if (!accepting || before < 0 || after < 0 || before > 32 || after > 32) return false;
                if (getText().length() != 0) return deleteLocal(before, after, true);
                return deleteSurroundingText(before, after);
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return handleKeyboardEvent(event); }
            @Override public boolean performEditorAction(int action) { return editKey(KeyEvent.KEYCODE_ENTER); }
        };
    }

    private boolean deleteLocal(int before, int after, boolean codePoints) {
        Editable text = getText();
        int start = Math.max(0, Math.min(getSelectionStart(), text.length()));
        int end = Math.max(start, Math.min(getSelectionEnd(), text.length()));
        int left, right;
        if (codePoints) {
            int leftCount = Character.codePointCount(text, 0, start);
            int rightCount = Character.codePointCount(text, end, text.length());
            left = Character.offsetByCodePoints(text, start, -Math.min(before, leftCount));
            right = Character.offsetByCodePoints(text, end, Math.min(after, rightCount));
        } else {
            left = Math.max(0, start - before); right = Math.min(text.length(), end + after);
            // Keep the private composing buffer valid even when a keyboard counts UTF-16 units.
            if (left > 0 && left < text.length() && Character.isLowSurrogate(text.charAt(left))
                    && Character.isHighSurrogate(text.charAt(left - 1))) left--;
            if (right > 0 && right < text.length() && Character.isHighSurrogate(text.charAt(right - 1))
                    && Character.isLowSurrogate(text.charAt(right))) right++;
        }
        text.delete(end, right); text.delete(left, start);
        setSelection(left, left + (end - start));
        return true;
    }

    @Override public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            if (!accepting) return false;
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                CharSequence text = data.getItemAt(0).coerceToText(getContext());
                return commitChunk(text);
            }
            return false;
        }
        return super.onTextContextMenuItem(id);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleKeyboardEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }
    private boolean handleKeyboardEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (!accepting || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return false;
        if (code == KeyEvent.KEYCODE_DEL && getText().length() > 0) return false;
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_TAB)
            return editKey(code);
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;
        int point = event.getUnicodeChar();
        if (point > 0 && Character.isValidCodePoint(point)) return commitChunk(new String(Character.toChars(point)));
        return false;
    }
}
