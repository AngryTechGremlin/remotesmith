package io.github.angrytechgremlin.remotesmith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the database the app ships. */
public class CodeDbTest {
    private static CodeDb db;
    private static Map<String, Vectors.Vector> vectors;

    @BeforeClass
    public static void load() throws IOException {
        try (FileInputStream in = new FileInputStream("src/main/assets/codes.txt")) {
            db = CodeDb.read(in);
        }
        vectors = Vectors.load();
    }

    private static byte[] vector(String name) {
        return vectors.get(name).code;
    }

    @Test
    public void everyCodeFitsTheRemote() {
        assertTrue(db.setCount() > 500);
        for (int set = 0; set < db.setCount(); set++) {
            assertNotNull(db.code(set, CodeDb.VOLUME_UP));
            assertNotNull(db.code(set, CodeDb.VOLUME_DOWN));
            for (int button = 0; button < CodeDb.BUTTONS; button++) {
                byte[] code = db.code(set, button);
                if (code != null) assertNull(IrCodec.problem(code));
            }
        }
    }

    @Test
    public void brandsAreListed() {
        List<String> brands = db.brands();
        assertTrue(brands.size() > 200);
        assertTrue(brands.containsAll(db.popularBrands()));
        assertEquals("Samsung", db.popularBrands().get(0));
        for (int i = 1; i < brands.size(); i++) {
            assertTrue(brands.get(i - 1).compareToIgnoreCase(brands.get(i)) < 0);
        }
    }

    @Test
    public void samsungStartsWithItsOwnCode() {
        int first = db.volumeCandidates("Samsung").get(0);
        assertArrayEquals(vector("samsung_volume_up"), db.code(first, CodeDb.VOLUME_UP));
        // A single press of Power must not send the frame twice.
        assertArrayEquals(vector("samsung_power_once"), db.buttonCandidates("Samsung", first, CodeDb.POWER).get(0));
    }

    @Test
    public void theSetProvenOnAHaierComesEarlyAndWhole() {
        List<Integer> candidates = db.volumeCandidates("Haier");
        int found = -1;
        for (int i = 0; i < 4; i++) {
            if (java.util.Arrays.equals(vector("nec04_volume_up"), db.code(candidates.get(i), CodeDb.VOLUME_UP))) found = candidates.get(i);
        }
        assertTrue("NEC 04 is not among Haier's first four", found >= 0);
        assertArrayEquals(vector("nec04_volume_down"), db.code(found, CodeDb.VOLUME_DOWN));
        assertArrayEquals(vector("nec04_mute"), db.buttonCandidates("Haier", found, CodeDb.MUTE).get(0));
        assertArrayEquals(vector("nec04_power"), db.buttonCandidates("Haier", found, CodeDb.POWER).get(0));
        assertArrayEquals(vector("nec04_input"), db.buttonCandidates("Haier", found, CodeDb.INPUT).get(0));
    }

    @Test
    public void anUnlistedTvStillGetsEveryPairOnce() {
        List<Integer> unlisted = db.volumeCandidates(null);
        assertEquals(unlisted, db.volumeCandidates("No Such Brand"));
        assertArrayEquals(vector("samsung_volume_up"), db.code(unlisted.get(0), CodeDb.VOLUME_UP));
        assertArrayEquals(vector("nec04_volume_up"), db.code(unlisted.get(1), CodeDb.VOLUME_UP));
        Set<String> pairs = new HashSet<>();
        for (int set : unlisted) {
            assertTrue(pairs.add(IrCodec.hex(db.code(set, CodeDb.VOLUME_UP)) + IrCodec.hex(db.code(set, CodeDb.VOLUME_DOWN))));
        }
        // A brand's list covers the same pairs, only in a different order.
        assertEquals(unlisted.size(), db.volumeCandidates("Haier").size());
    }

    @Test
    public void buttonCandidatesNeverRepeat() {
        int set = db.volumeCandidates("LG").get(0);
        for (int button : new int[] {CodeDb.MUTE, CodeDb.POWER, CodeDb.INPUT}) {
            List<byte[]> candidates = db.buttonCandidates("LG", set, button);
            Set<String> seen = new HashSet<>();
            for (byte[] code : candidates) assertTrue(seen.add(IrCodec.hex(code)));
            assertTrue(candidates.size() > 50);
        }
    }

    @Test
    public void refusesADamagedFile() {
        for (String bad : new String[] {"", "something else\n", "remotesmith-codes 1\nC zz\n",
                "remotesmith-codes 1\nS 0 x - - -\n", "remotesmith-codes 1\nB NoTab 1 2\n"}) {
            try {
                CodeDb.read(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)));
                fail("accepted: " + bad);
            } catch (IOException expected) {
                // refused, as it should be
            }
        }
    }
}
