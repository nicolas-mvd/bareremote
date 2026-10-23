package uy.nico.bareremote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded, session-local Android TV IME metadata and commit-text encoder. */
final class KeyboardSession {
    static final int MAX_TEXT = 4096;
    static final int MAX_TEXT_BYTES = 16384;
    private boolean supported, active, countersReady, showRequested;
    private int imeCounter, fieldCounter, shownField = -1;
    private long focusID;

    synchronized KeyboardState snapshot() {
        return new KeyboardState(supported, active, countersReady && (shownField < 0 || shownField == fieldCounter), showRequested,
                imeCounter, fieldCounter, focusID);
    }
    synchronized void supported(boolean value) {
        supported = value;
        if (!value) clear();
    }
    synchronized void clear() {
        supported = false;
        active = false;
        showRequested = false;
        countersReady = false;
        shownField = -1;
        imeCounter = fieldCounter = 0;
        focusID++;
    }

    synchronized boolean receive(Proto.Message message) throws IOException {
        if (!supported) return false;
        boolean changed = false;
        // This message also reports foreground apps; it is not a keyboard-show signal.
        if (message.has(20)) {
            Proto.Message inject = message.message(20);
            if (inject.has(1)) {
                Proto.Message app = inject.message(1);
                boundedCounter(app, 1);
                validateText(app.bytes(12), 512);
            }
            if (!inject.has(2)) {
                if (active) { active = false; showRequested = false; shownField = -1; focusID++; changed = true; }
            } else {
                int field = validateField(inject.message(2));
                if (!(countersReady && field < fieldCounter)) {
                    // Authenticated context permits an explicit manual keyboard even if SHOW is absent.
                    // This metadata alone never requests an automatic UI switch.
                    if (!active || field != shownField) { focusID++; showRequested = false; }
                    active = true; shownField = field; changed = true;
                }
            }
        }
        if (message.has(21)) {
            Proto.Message batch = message.message(21);
            int ime = boundedCounter(batch, 1), field = boundedCounter(batch, 2);
            int edits = 0;
            for (Proto.Field item : batch.fields) if (item.number == 3) {
                if (++edits > 64 || item.wire != 2) throw new IOException("Invalid TV keyboard update");
                Proto.Message edit = Proto.parse(item.bytes);
                boundedCounter(edit, 1);
                if (edit.has(2)) {
                    Proto.Message object = edit.message(2);
                    boundedPosition(object, 1); boundedPosition(object, 2);
                    validateText(object.bytes(3), MAX_TEXT_BYTES);
                }
            }
            // Older notifications cannot move a queued edit to a previous field.
            if (!countersReady || ime > imeCounter || (ime == imeCounter && field >= fieldCounter)) {
                changed |= !countersReady || ime != imeCounter || field != fieldCounter;
                imeCounter = ime; fieldCounter = field; countersReady = true;
            }
        }
        if (message.has(22)) {
            Proto.Message show = message.message(22);
            if (!show.has(2)) {
                if (active) { active = false; showRequested = false; shownField = -1; focusID++; changed = true; }
            } else {
                int field = validateField(show.message(2));
                if (!(countersReady && field >= 0 && field < fieldCounter)) {
                    // Duplicate SHOW for the same field preserves the manual Remote override.
                    if (!active || shownField != field) focusID++;
                    active = true; showRequested = true; shownField = field; changed = true;
                }
            }
        }
        return changed;
    }

    synchronized Proto.Writer commit(String text, KeyboardState expected) throws IOException {
        validateCommit(text);
        KeyboardState current = snapshot();
        if (!current.canType()) throw new IOException("Select a text field on the TV first");
        if (!current.sameField(expected)) throw new IOException("TV text field changed; type again");
        Proto.Writer object = new Proto.Writer();
        // Remote v2's established commitText form. Positions count UTF-16 code units.
        int end = text.length() - 1;
        if (end != 0) object.number(1, end).number(2, end);
        object.text(3, text);
        Proto.Writer batch = new Proto.Writer();
        // Match proto3 default encoding; explicit zero counters are rejected by some services.
        if (imeCounter != 0) batch.number(1, imeCounter);
        if (fieldCounter != 0) batch.number(2, fieldCounter);
        batch.message(3, new Proto.Writer().number(1, 1).message(2, object));
        return new Proto.Writer().message(21, batch);
    }

    synchronized void checkField(KeyboardState expected) throws IOException {
        if (!snapshot().canType() || !snapshot().sameField(expected))
            throw new IOException("TV text field changed; select a text field first");
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
    private static int validateField(Proto.Message field) throws IOException {
        int counter = boundedCounter(field, 1);
        validateText(field.bytes(2), MAX_TEXT_BYTES);
        boundedPosition(field, 3); boundedPosition(field, 4);
        field.integer(5, 0); // Undocumented editor metadata; never infer a password/text mode from it.
        validateText(field.bytes(6), 1024);
        return counter;
    }
    private static int boundedCounter(Proto.Message message, int field) throws IOException {
        long value = message.integer(field, 0);
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid TV keyboard counter");
        return (int) value;
    }
    private static void boundedPosition(Proto.Message message, int field) throws IOException {
        long value = message.integer(field, 0);
        if (value != -1 && (value < 0 || value > 32768)) throw new IOException("Invalid TV text position");
    }
    private static void validateText(byte[] bytes, int limit) throws IOException {
        if (bytes == null) return;
        if (bytes.length > limit) throw new IOException("TV keyboard update is too large");
        try {
            // Validate untrusted UTF-8 without retaining a field-value String.
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException invalid) { throw new IOException("Invalid TV keyboard text"); }
    }
}
