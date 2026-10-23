package uy.nico.bareremote;

/** Ephemeral IME metadata only. TV field contents are deliberately never retained. */
public final class KeyboardState {
    public final boolean supported, active, countersReady, showRequested;
    public final int imeCounter, fieldCounter;
    public final long focusID;

    KeyboardState(boolean supported, boolean active, boolean countersReady,
            int imeCounter, int fieldCounter, long focusID) {
        this(supported, active, countersReady, active, imeCounter, fieldCounter, focusID);
    }
    KeyboardState(boolean supported, boolean active, boolean countersReady, boolean showRequested,
            int imeCounter, int fieldCounter, long focusID) {
        this.supported = supported;
        this.showRequested = showRequested;
        this.active = active;
        this.countersReady = countersReady;
        this.imeCounter = imeCounter;
        this.fieldCounter = fieldCounter;
        this.focusID = focusID;
    }
    public boolean canType() { return supported && active && countersReady; }
    static KeyboardState unavailable() { return new KeyboardState(false, false, false, 0, 0, 0); }
    boolean sameField(KeyboardState other) {
        return other != null && active && other.active && focusID == other.focusID
                && fieldCounter == other.fieldCounter;
    }
}
