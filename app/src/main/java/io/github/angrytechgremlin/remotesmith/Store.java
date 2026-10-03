package io.github.angrytechgremlin.remotesmith;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** The setup last put on a remote, kept so it can be sent to a second remote or after a reset. */
final class Store {
    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE);
    }

    boolean exists() {
        return prefs.contains("table");
    }

    /** What the setup was made for: a brand name, or null for a TV whose brand is not listed. */
    String brand() {
        return prefs.getString("brand", null);
    }

    void save(String brand, SortedMap<Integer, byte[]> table) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, byte[]> e : table.entrySet()) {
            text.append(e.getKey()).append('=').append(IrCodec.hex(e.getValue())).append('\n');
        }
        prefs.edit().putString("brand", brand).putString("table", text.toString()).apply();
    }

    /** The saved table by keycode; empty if nothing is saved or it cannot be read. */
    SortedMap<Integer, byte[]> table() {
        SortedMap<Integer, byte[]> table = new TreeMap<>();
        try {
            for (String line : prefs.getString("table", "").split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                byte[] code = IrCodec.unhex(line.substring(eq + 1));
                if (IrCodec.problem(code) == null) table.put(Integer.parseInt(line.substring(0, eq)), code);
            }
        } catch (IllegalArgumentException e) {
            table.clear();
        }
        return table;
    }
}
