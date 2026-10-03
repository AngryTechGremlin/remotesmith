package io.github.angrytechgremlin.remotesmith;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One run of the setup wizard: which codes are being tried, which are confirmed, and what to
 * send to the remote next. The volume buttons are settled first, as a pair from one code set;
 * mute, power and input then each get the best remaining guess, one button at a time.
 *
 * Nothing here touches Bluetooth or the screen, so a run can be saved, restored and tested.
 */
final class Setup {
    enum Step { VOLUME, MUTE, POWER, INPUT, DONE }

    private static final Step[] ORDER = Step.values();
    private static final int[][] KEYCODES = new int[CodeDb.BUTTONS][];
    static {
        KEYCODES[CodeDb.VOLUME_UP] = IrCodec.KEYCODES.get("volume_up");
        KEYCODES[CodeDb.VOLUME_DOWN] = IrCodec.KEYCODES.get("volume_down");
        KEYCODES[CodeDb.MUTE] = IrCodec.KEYCODES.get("mute");
        KEYCODES[CodeDb.POWER] = IrCodec.KEYCODES.get("power");
        KEYCODES[CodeDb.INPUT] = IrCodec.KEYCODES.get("input");
    }

    private final CodeDb db;
    private final String brand;             // null: the TV's brand is not listed
    private final List<Integer> volume;     // sets to try for the volume pair
    private int volumeAt;
    private Step step = Step.VOLUME;
    private final byte[][] chosen = new byte[CodeDb.BUTTONS][];   // confirmed codes
    private List<byte[]> candidates;        // codes to try for the current single button
    private int candidateAt;

    Setup(CodeDb db, String brand) {
        this.db = db;
        this.brand = brand;
        this.volume = db.volumeCandidates(brand);
    }

    String brand() {
        return brand;
    }

    Step step() {
        return step;
    }

    /** Which candidate is being tried, counting from 1. */
    int attempt() {
        return (step == Step.VOLUME ? volumeAt : candidateAt) + 1;
    }

    /** How many candidates the current step has. */
    int attempts() {
        return step == Step.VOLUME ? volume.size() : candidates == null ? 0 : candidates.size();
    }

    /** How many of the volume candidates come from the chosen brand itself. */
    int ownVolumeAttempts() {
        return db.ownVolumeCandidates(brand);
    }

    /** The Android keycodes the remote reports for the buttons of the current step. */
    int[] keysUnderTest() {
        switch (step) {
            case VOLUME: return new int[] {24, 25};
            case MUTE: return KEYCODES[CodeDb.MUTE];
            case POWER: return KEYCODES[CodeDb.POWER];
            case INPUT: return KEYCODES[CodeDb.INPUT];
            default: return new int[0];
        }
    }

    /**
     * The whole table to send now, by keycode: confirmed codes, the candidate under test, and
     * a first guess for buttons not reached yet. A programming session replaces everything on
     * the remote, so this is always the full set.
     */
    SortedMap<Integer, byte[]> table() {
        SortedMap<Integer, byte[]> table = new TreeMap<>();
        for (int button = 0; button < CodeDb.BUTTONS; button++) {
            byte[] code = codeFor(button);
            if (code == null) continue;
            for (int keycode : KEYCODES[button]) table.put(keycode, code);
        }
        return table;
    }

    /** Only what the user has confirmed so far, by keycode: what stays if the run stops here. */
    SortedMap<Integer, byte[]> confirmed() {
        SortedMap<Integer, byte[]> table = new TreeMap<>();
        for (int button = 0; button < CodeDb.BUTTONS; button++) {
            if (chosen[button] == null) continue;
            for (int keycode : KEYCODES[button]) table.put(keycode, chosen[button]);
        }
        return table;
    }

    /** Whether a button ended up with a confirmed code. */
    boolean has(int button) {
        return chosen[button] != null;
    }

    private byte[] codeFor(int button) {
        if (step == Step.VOLUME) return db.code(volume.get(volumeAt), button);
        if (button == CodeDb.VOLUME_UP || button == CodeDb.VOLUME_DOWN) return chosen[button];
        int current = buttonOf(step);
        if (button == current) return candidates.get(candidateAt);
        if (step == Step.DONE || stepOf(button).ordinal() < step.ordinal()) return chosen[button];   // null if skipped
        List<byte[]> guesses = db.buttonCandidates(brand, volume.get(volumeAt), button);
        return guesses.isEmpty() ? null : guesses.get(0);
    }

    /** The candidate under test works: keep it and move on to the next button. */
    void works() {
        if (step == Step.VOLUME) {
            chosen[CodeDb.VOLUME_UP] = db.code(volume.get(volumeAt), CodeDb.VOLUME_UP);
            chosen[CodeDb.VOLUME_DOWN] = db.code(volume.get(volumeAt), CodeDb.VOLUME_DOWN);
        } else if (step != Step.DONE) {
            chosen[buttonOf(step)] = candidates.get(candidateAt);
        }
        advance();
    }

    /** The candidate under test does not work: try the next. False if there is none left. */
    boolean next() {
        if (step == Step.VOLUME) {
            if (volumeAt + 1 >= volume.size()) return false;
            volumeAt++;
            return true;
        }
        if (step == Step.DONE || candidateAt + 1 >= candidates.size()) return false;
        candidateAt++;
        return true;
    }

    /** Return to the candidate before this one. False at the first. */
    boolean previous() {
        if (step == Step.VOLUME) {
            if (volumeAt == 0) return false;
            volumeAt--;
            return true;
        }
        if (step == Step.DONE || candidateAt == 0) return false;
        candidateAt--;
        return true;
    }

    /** Leave the current button without a TV code; it keeps acting on the box. Not for volume. */
    void skip() {
        if (step == Step.VOLUME || step == Step.DONE) throw new IllegalStateException("nothing to skip");
        chosen[buttonOf(step)] = null;
        advance();
    }

    private void advance() {
        do {
            step = ORDER[step.ordinal() + 1];
            if (step == Step.DONE) return;
            candidates = db.buttonCandidates(brand, volume.get(volumeAt), buttonOf(step));
            candidateAt = 0;
        } while (candidates.isEmpty());
    }

    private static int buttonOf(Step step) {
        switch (step) {
            case MUTE: return CodeDb.MUTE;
            case POWER: return CodeDb.POWER;
            case INPUT: return CodeDb.INPUT;
            default: return -1;
        }
    }

    private static Step stepOf(int button) {
        return button == CodeDb.MUTE ? Step.MUTE : button == CodeDb.POWER ? Step.POWER
                : button == CodeDb.INPUT ? Step.INPUT : Step.VOLUME;
    }

    // --- saving a run -----------------------------------------------------------------------

    /** The run as text; {@link #restore} brings it back against the same database. */
    String save() {
        StringBuilder sb = new StringBuilder();
        sb.append(step.name()).append('\n').append(volumeAt).append('\n').append(candidateAt).append('\n');
        for (byte[] code : chosen) sb.append(code == null ? "-" : IrCodec.hex(code)).append('\n');
        sb.append(brand == null ? "" : brand);
        return sb.toString();
    }

    /** A saved run, or null if it does not fit this database (after an app update, say). */
    static Setup restore(CodeDb db, String saved) {
        try {
            String[] f = saved.split("\n", -1);
            Setup s = new Setup(db, f[3 + CodeDb.BUTTONS].isEmpty() ? null : f[3 + CodeDb.BUTTONS]);
            s.step = Step.valueOf(f[0]);
            s.volumeAt = Integer.parseInt(f[1]);
            s.candidateAt = Integer.parseInt(f[2]);
            for (int b = 0; b < CodeDb.BUTTONS; b++) s.chosen[b] = f[3 + b].equals("-") ? null : IrCodec.unhex(f[3 + b]);
            if (s.volumeAt < 0 || s.volumeAt >= s.volume.size()) return null;
            if (s.step != Step.VOLUME && s.step != Step.DONE) {
                s.candidates = db.buttonCandidates(s.brand, s.volume.get(s.volumeAt), buttonOf(s.step));
                if (s.candidateAt < 0 || s.candidateAt >= s.candidates.size()) return null;
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
