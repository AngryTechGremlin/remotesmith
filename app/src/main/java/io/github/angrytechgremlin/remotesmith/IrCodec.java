package io.github.angrytechgremlin.remotesmith;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The remote's stored IR code format (docs/protocol.md, "Code format"): checks ready-made codes
 * and packs Pronto hex into it. Mirrors tools/ircodes.py.
 *
 * <pre>
 * byte 0     form: 1 once, 2 repeat only, 3 once + repeat, 4 two sequences sent on alternate presses
 * byte 1     duty cycle in percent (33 for a modulated carrier, 100 for none)
 * bytes 2-3  carrier in units of 100 Hz
 * form 1:    pairs, then mark/space pairs
 * form 2:    pairs, period (sum of all durations), then pairs
 * form 3, 4: pairs of the first sequence, pairs of the second, then both sequences
 * </pre>
 * Every number is 16-bit big-endian and every duration counts carrier cycles.
 */
public final class IrCodec {
    /** Microseconds per unit of a Pronto frequency word. */
    private static final double PRONTO_UNIT = 0.241246;

    /** The remote keeps at most this many bytes per button. */
    public static final int MAX_CODE_BYTES = 300;

    /**
     * Android keycodes the remote addresses its IR keys by, in the ascending order they are
     * written. A remote has either an Input button (178) or a customizable ★ button (313) in the
     * same slot and ignores the id it lacks, so the input code goes to both.
     */
    public static final Map<String, int[]> KEYCODES = new LinkedHashMap<>();
    static {
        KEYCODES.put("volume_up", new int[] {24});
        KEYCODES.put("volume_down", new int[] {25});
        KEYCODES.put("power", new int[] {26});
        KEYCODES.put("mute", new int[] {164});
        KEYCODES.put("input", new int[] {178, 313});
    }

    private IrCodec() {}

    /** A profile value: Pronto hex, or "hex:" followed by a ready-made remote code. */
    public static byte[] parse(String value) {
        String v = value.trim();
        byte[] code = v.startsWith("hex:") ? unhex(v.substring(4).trim()) : encode(v);
        String problem = problem(code);
        if (problem != null) throw new IllegalArgumentException(problem);
        return code;
    }

    /** Why the remote could not store or play this code, or null if it is well-formed. */
    public static String problem(byte[] c) {
        if (c.length > MAX_CODE_BYTES) {
            return "code is " + c.length + " bytes; the remote keeps at most " + MAX_CODE_BYTES;
        }
        if (c.length < 6) return "code is too short";
        int form = c[0] & 0xFF, duty = c[1] & 0xFF, carrier = u16(c, 2);
        int expected;
        if (form == 1) {
            expected = 6 + 4 * u16(c, 4);
        } else if (form == 2) {
            expected = 8 + 4 * u16(c, 4);
        } else if ((form == 3 || form == 4) && c.length >= 8) {
            expected = 8 + 4 * (u16(c, 4) + u16(c, 6));
        } else {
            return "unknown code form " + form;
        }
        if (expected != c.length) return "code is " + c.length + " bytes but its header says " + expected;
        if (duty < 1 || duty > 100) return "duty cycle " + duty + "% is out of range";
        // The remote divides by the carrier; 10 kHz to 500 kHz covers every consumer IR system.
        if (carrier < 100 || carrier > 5000) return "carrier " + carrier * 100 + " Hz is out of range";
        return null;
    }

    /** Pack raw Pronto hex (formats 0000 and 0100) into the remote's format. */
    public static byte[] encode(String pronto) {
        String[] parts = pronto.trim().split("\\s+");
        int[] w = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) w[i] = Integer.parseInt(parts[i], 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not Pronto hex: " + e.getMessage());
        }
        if (w.length < 4 || (w[0] != 0x0000 && w[0] != 0x0100)) {
            throw new IllegalArgumentException("only raw Pronto (0000/0100) is supported");
        }
        int once = w[2], rpt = w[3];
        if (w.length - 4 != 2 * (once + rpt) || once + rpt == 0) {
            throw new IllegalArgumentException("Pronto pair counts do not match its length");
        }
        if (w[1] == 0) throw new IllegalArgumentException("Pronto frequency word is zero");
        int carrier = (int) Math.round(1e6 / (w[1] * PRONTO_UNIT) / 100);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((once > 0 ? 1 : 0) | (rpt > 0 ? 2 : 0));
        out.write(w[0] == 0x0100 ? 100 : 33);
        u16(out, carrier);
        if (once > 0) u16(out, once);
        if (rpt > 0) {
            u16(out, rpt);
            if (once == 0) {
                int sum = 0;
                for (int i = 4; i < w.length; i++) sum += w[i];
                u16(out, sum);
            }
        }
        for (int i = 4; i < w.length; i++) u16(out, w[i]);
        return out.toByteArray();
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static void u16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("hex code has an odd number of digits");
        byte[] out = new byte[s.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a hex code: " + e.getMessage());
        }
        return out;
    }
}
