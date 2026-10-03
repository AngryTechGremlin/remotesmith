package io.github.angrytechgremlin.remotesmith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The bundled TV code database (assets/codes.txt), as tools/build_db.py writes it: unique codes,
 * the sets built from them (one code per button), each brand's sets in the order worth trying,
 * and an order for TVs whose brand is not listed.
 */
final class CodeDb {
    /** Position of each button's code within a set. */
    static final int VOLUME_UP = 0, VOLUME_DOWN = 1, MUTE = 2, POWER = 3, INPUT = 4;
    static final int BUTTONS = 5;
    private static final String HEADER = "remotesmith-codes 1";

    private final List<byte[]> codes = new ArrayList<>();
    private final List<int[]> sets = new ArrayList<>();   // code index per button, -1 = none
    private final Map<String, int[]> brands = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> popular = new ArrayList<>();
    private int[] common = new int[0];

    private CodeDb() {}

    static CodeDb read(InputStream in) throws IOException {
        CodeDb db = new CodeDb();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (!HEADER.equals(r.readLine())) throw new IOException("not a code database this version reads");
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.length() < 2 || line.charAt(1) != ' ') continue;
                String rest = line.substring(2);
                switch (line.charAt(0)) {
                    case 'C':
                        db.codes.add(IrCodec.unhex(rest));
                        break;
                    case 'S':
                        db.sets.add(indexes(rest, true));
                        break;
                    case 'B':
                        int tab = rest.indexOf('\t');
                        db.brands.put(rest.substring(0, tab), indexes(rest.substring(tab + 1), false));
                        break;
                    case 'P':
                        for (String name : rest.split("\t")) db.popular.add(name);
                        break;
                    case 'G':
                        db.common = indexes(rest, false);
                        break;
                    default:
                        break;
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("damaged code database: " + e.getMessage());
        }
        return db;
    }

    private static int[] indexes(String text, boolean gaps) {
        String[] parts = text.trim().split(" ");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = gaps && parts[i].equals("-") ? -1 : Integer.parseInt(parts[i]);
        }
        return out;
    }

    /** Every brand, in alphabetical order. */
    List<String> brands() {
        return new ArrayList<>(brands.keySet());
    }

    /** The brands to show first. */
    List<String> popularBrands() {
        return new ArrayList<>(popular);
    }

    int setCount() {
        return sets.size();
    }

    /** The code a set has for a button, or null. */
    byte[] code(int set, int button) {
        int index = sets.get(set)[button];
        return index < 0 ? null : codes.get(index);
    }

    /**
     * Sets to try for the volume buttons, best guess first: the brand's own (none for a null or
     * unknown brand), then every other one. Each has a different pair of volume codes.
     */
    List<Integer> volumeCandidates(String brand) {
        List<Integer> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (int set : tryOrder(brand)) {
            if (seen.add(pair(set))) out.add(set);
        }
        return out;
    }

    /** How many of a brand's volume candidates are its own; the rest are other brands' sets. */
    int ownVolumeCandidates(String brand) {
        int[] own = brand == null ? null : brands.get(brand);
        if (own == null) return 0;
        Set<Long> seen = new LinkedHashSet<>();
        for (int set : own) seen.add(pair(set));
        return seen.size();
    }

    /**
     * Codes to try for one of the other buttons once the volume buttons of {@code volumeSet}
     * are known to work, best guess first: from sets with the same volume codes, then from the
     * brand's other sets, then from all the rest. No code appears twice.
     */
    List<byte[]> buttonCandidates(String brand, int volumeSet, int button) {
        long pair = pair(volumeSet);
        List<Integer> order = new ArrayList<>();
        List<Integer> general = tryOrder(brand);
        for (int set : general) if (pair(set) == pair) order.add(set);
        for (int set = 0; set < sets.size(); set++) if (pair(set) == pair) order.add(set);
        order.addAll(general);
        List<byte[]> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int set : order) {
            int index = sets.get(set)[button];
            if (index >= 0 && seen.add(index)) out.add(codes.get(index));
        }
        return out;
    }

    /** The brand's sets, then the order for unlisted TVs, then whatever is left. */
    private List<Integer> tryOrder(String brand) {
        Set<Integer> order = new LinkedHashSet<>();
        int[] own = brand == null ? null : brands.get(brand);
        if (own != null) for (int set : own) order.add(set);
        for (int set : common) order.add(set);
        for (int set = 0; set < sets.size(); set++) order.add(set);
        return new ArrayList<>(order);
    }

    private long pair(int set) {
        int[] s = sets.get(set);
        return ((long) s[VOLUME_UP] << 32) | (s[VOLUME_DOWN] & 0xFFFFFFFFL);
    }
}
