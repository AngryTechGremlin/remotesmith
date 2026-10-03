package io.github.angrytechgremlin.remotesmith;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A setup written by hand, for a TV the bundled database has no code for: JSON of
 * {"volume_up": CODE, "power": CODE, …} where CODE is Pronto hex, or "hex:" followed by a code
 * already in the remote's format.
 */
final class Profiles {
    private Profiles() {}

    /** The profile as a table by keycode, ready to send. Throws IllegalArgumentException with the reason if it cannot be used. */
    static SortedMap<Integer, byte[]> parse(String json) {
        SortedMap<Integer, byte[]> table = new TreeMap<>();
        try {
            JSONObject profile = new JSONObject(json);
            for (Iterator<String> names = profile.keys(); names.hasNext(); ) {
                String name = names.next();
                int[] keycodes = IrCodec.KEYCODES.get(name);
                if (keycodes == null) throw new IllegalArgumentException("unknown button \"" + name + "\"");
                byte[] code;
                try {
                    code = IrCodec.parse(profile.getString(name));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(name + ": " + e.getMessage());
                }
                for (int keycode : keycodes) table.put(keycode, code);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (table.isEmpty()) throw new IllegalArgumentException("it lists no buttons");
        return table;
    }
}
