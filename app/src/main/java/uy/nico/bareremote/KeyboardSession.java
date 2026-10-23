package uy.nico.bareremote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Direction-specific Android TV IME state. Stores coordinates, never TV field contents. */
final class KeyboardSession {
    static final int MAX_TEXT = 4096;
    static final int MAX_TEXT_BYTES = 16384;
    private boolean supported, active, countersReady, optimisticSelection;
    private Boolean keyboardVisible;
    private int imeCounter, fieldCounter, selectionStart = -1, selectionEnd = -1;
    private int textLength = -1;
    private String appPackage = "";
    private long focusID, showRequestID;
    private int shownEditor = -1;

    synchronized KeyboardState snapshot() {
        return new KeyboardState(supported, active, countersReady, Boolean.TRUE.equals(keyboardVisible),
                imeCounter, fieldCounter, focusID, keyboardVisible, showRequestID);
    }
    synchronized void supported(boolean value) {
        supported = value;
        if (!value) clear();
    }
    synchronized void clear() {
        supported = false;
        blur();
    }
    private void blur() {
        active = countersReady = optimisticSelection = false;
        keyboardVisible = null;
        imeCounter = fieldCounter = 0;
        selectionStart = selectionEnd = -1;
        textLength = -1;
        appPackage = "";
        shownEditor = -1;
        focusID++;
    }

    synchronized boolean receive(Proto.Message message) throws IOException {
        if (!supported) return false;
        boolean changed = false;
        // Server and client envelope numbers share names but NOT message schemas.
        // Server 20 starts an editor; its app counter is the stable editor identity.
        if (message.has(20)) {
            Proto.Message editor = message.message(20);
            if (!editor.has(1)) {
                blur(); changed = true;
            } else {
                Proto.Message info = editor.message(1);
                int id = boundedCounter(info, 1);
                long inputType = info.integer(2, 0);
                if (inputType < 0 || inputType > Integer.MAX_VALUE) throw new IOException("Invalid TV editor type");
                String app = validateText(info.bytes(12), 512);
                if (id == 0 || inputType == 0) {
                    blur(); changed = true;
                } else {
                    boolean newEditor = !active || id != imeCounter || !appPackage.equals(app);
                    if (newEditor) {
                        focusID++;
                        fieldCounter = 0;
                        selectionStart = selectionEnd = -1;
                        textLength = -1;
                        optimisticSelection = false;
                        shownEditor = -1;
                    }
                    active = true; imeCounter = id; appPackage = app;
                    if (editor.has(2)) applyField(editor.message(2), false, !newEditor);
                    else {
                        fieldCounter = 0;
                        position(info, 7, -1); position(info, 8, -1);
                        selectionStart = selectionEnd = -1;
                        textLength = -1;
                        optimisticSelection = false;
                        countersReady = false;
                    }
                    requestShow();
                    changed = true;
                }
            }
        }
        // Server 21 is visibility (bool + flags), NOT the client batch-edit counters.
        if (message.has(21)) {
            Proto.Message visible = message.message(21);
            long show = visible.integer(1, 0);
            if (show != 0 && show != 1) throw new IOException("Invalid TV keyboard visibility");
            visible.integer(2, 0); // IME show flags; not an editor/content counter.
            keyboardVisible = show == 1;
            requestShow();
            changed = true;
        }
        // Server 22 updates extracted text and identifies an acknowledgment of our own edits.
        if (message.has(22)) {
            Proto.Message update = message.message(22);
            long ownEdit = update.integer(1, 0);
            if (ownEdit != 0 && ownEdit != 1) throw new IOException("Invalid TV keyboard acknowledgment");
            if (update.has(2)) {
                Proto.Message field = update.message(2);
                validateField(field);
                if (active) applyField(field, ownEdit == 1, true);
                changed = true;
            }
        }
        // Server 23 is a selection update; the real selection schema uses fields 2/3.
        if (message.has(23)) {
            Proto.Message selection = message.message(23);
            int start = position(selection, 2, 0), end = position(selection, 3, 0);
            if (active) {
                if (start != selectionStart || end != selectionEnd) focusID++;
                selectionStart = start; selectionEnd = end;
                optimisticSelection = false;
                countersReady = start >= 0 && end >= 0;
                changed = true;
            }
        }
        if (message.has(24)) {
            message.message(24);
            if (active) {
                // Another remote/external key changed this editor. Wait for fresh coordinates.
                focusID++; optimisticSelection = countersReady = false;
                selectionStart = selectionEnd = textLength = -1; changed = true;
            }
        }
        return changed;
    }

    private void requestShow() {
        if (active && Boolean.TRUE.equals(keyboardVisible) && shownEditor != imeCounter) {
            shownEditor = imeCounter;
            showRequestID++;
        }
    }
    private void applyField(Proto.Message field, boolean ownEdit, boolean invalidateExternal) throws IOException {
        validateField(field);
        int revision = boundedCounter(field, 1);
        if (revision < fieldCounter) return;
        int start = position(field, 3, 0), end = position(field, 4, 0);
        if (ownEdit && optimisticSelection) {
            // This can acknowledge an earlier chunk while later chunks are already written.
            // Its cursor must not rewind our predicted cursor. The service marks transformed
            // or externally modified text as an external update instead.
            fieldCounter = revision;
            return;
        }
        if (invalidateExternal && (revision != fieldCounter || start != selectionStart || end != selectionEnd))
            focusID++;
        fieldCounter = revision;
        selectionStart = start; selectionEnd = end;
        String value = validateText(field.bytes(2), MAX_TEXT_BYTES);
        textLength = field.has(2) && value.length() >= Math.max(start, end) ? value.length() : -1;
        optimisticSelection = false;
        countersReady = start >= 0 && end >= 0;
    }

    synchronized Proto.Writer commit(String text, KeyboardState expected) throws IOException {
        validateCommit(text);
        if (!snapshot().canType()) throw new KeyboardState.InputUnavailableException("Select a text field on the TV first");
        if (!snapshot().sameField(expected)) throw new KeyboardState.InputUnavailableException("TV text field changed; type again");
        int start = Math.min(selectionStart, selectionEnd), end = Math.max(selectionStart, selectionEnd);
        int next = start + text.length();
        if (next > 32768) throw new KeyboardState.InputUnavailableException("TV text position is too large");
        Proto.Writer object = new Proto.Writer();
        if (start != 0) object.number(1, start);
        if (end != 0) object.number(2, end);
        object.text(3, text);
        // Client 21 is {editor id, content revision, repeated edit-oneof}; its replacement
        // is field 2. There is no insert=1 integer in the real edit-oneof schema.
        Proto.Writer batch = new Proto.Writer().number(1, imeCounter);
        if (fieldCounter != 0) batch.number(2, fieldCounter);
        batch.message(3, new Proto.Writer().message(2, object));
        if (textLength >= 0) textLength += text.length() - (end - start);
        selectionStart = selectionEnd = next;
        optimisticSelection = true;
        return new Proto.Writer().message(21, batch);
    }

    synchronized void checkField(KeyboardState expected) throws IOException {
        if (!active || !snapshot().sameField(expected))
            throw new KeyboardState.InputUnavailableException("TV text field changed; select a text field first");
    }
    synchronized void keyWritten(int key) {
        // These may leave the editor unchanged without producing an update. Keep usable
        // coordinates until an actual editor/status notification says otherwise.
        if (key == 66 || key == 61) return;
        if (selectionStart == selectionEnd && selectionStart >= 0) {
            if ((key == 67 || key == 21) && selectionStart == 0) return;
            if ((key == 112 || key == 22) && textLength >= 0 && selectionEnd == textLength) return;
        }
        // Android key events may delete a surrogate pair or move the cursor. The next
        // service status/selection supplies the exact UTF-16 coordinates before text resumes.
        optimisticSelection = countersReady = false;
        selectionStart = selectionEnd = -1;
    }
    static void validateCommit(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT)
            throw new IllegalArgumentException("Send between 1 and 4096 characters at a time");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode text");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("Text is too long");
    }
    private static void validateField(Proto.Message field) throws IOException {
        boundedCounter(field, 1);
        validateText(field.bytes(2), MAX_TEXT_BYTES);
        position(field, 3, 0); position(field, 4, 0);
        field.integer(5, 0);
        validateText(field.bytes(6), 1024);
    }
    private static int boundedCounter(Proto.Message message, int field) throws IOException {
        long value = message.integer(field, 0);
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid TV keyboard counter");
        return (int) value;
    }
    private static int position(Proto.Message message, int field, int fallback) throws IOException {
        long value = message.integer(field, fallback);
        if (value != -1 && (value < 0 || value > 32768)) throw new IOException("Invalid TV text position");
        return (int) value;
    }
    private static String validateText(byte[] bytes, int limit) throws IOException {
        if (bytes == null) return "";
        if (bytes.length > limit) throw new IOException("TV keyboard update is too large");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid TV keyboard text"); }
    }
}
