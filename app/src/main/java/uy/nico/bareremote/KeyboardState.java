package uy.nico.bareremote;

/** Ephemeral IME metadata only. TV field contents are deliberately never retained. */
public final class KeyboardState {
    /** A rejected, unwritten edit must not tear down an otherwise healthy TV session. */
    static final class InputUnavailableException extends java.io.IOException {
        InputUnavailableException(String message) { super(message); }
    }
    public final boolean supported, active, countersReady, showRequested;
    public final int imeCounter, fieldCounter;
    public final long focusID;
    /** Service visibility notification; null means it has not reported visibility yet. */
    public final Boolean keyboardVisible;
    public final long showRequestID;

    KeyboardState(boolean supported, boolean active, boolean countersReady,
            int imeCounter, int fieldCounter, long focusID) {
        this(supported, active, countersReady, active, imeCounter, fieldCounter, focusID);
    }
    KeyboardState(boolean supported, boolean active, boolean countersReady, boolean showRequested,
            int imeCounter, int fieldCounter, long focusID) {
        this(supported, active, countersReady, showRequested, imeCounter, fieldCounter, focusID, null, 0);
    }
    KeyboardState(boolean supported, boolean active, boolean countersReady, boolean showRequested,
            int imeCounter, int fieldCounter, long focusID, Boolean keyboardVisible, long showRequestID) {
        this.supported = supported;
        this.showRequested = showRequested;
        this.active = active;
        this.countersReady = countersReady;
        this.imeCounter = imeCounter;
        this.fieldCounter = fieldCounter;
        this.focusID = focusID;
        this.keyboardVisible = keyboardVisible;
        this.showRequestID = showRequestID;
    }
    public boolean canType() { return supported && active && countersReady; }
    static KeyboardState unavailable() { return new KeyboardState(false, false, false, 0, 0, 0); }
    boolean sameField(KeyboardState other) {
        return other != null && active && other.active && focusID == other.focusID
                && imeCounter == other.imeCounter;
    }
}
