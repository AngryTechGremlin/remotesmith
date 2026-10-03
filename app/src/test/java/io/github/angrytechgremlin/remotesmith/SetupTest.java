package io.github.angrytechgremlin.remotesmith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;

public class SetupTest {
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

    /** Walk a Haier owner to the set that was proven on a real Haier. */
    private static Setup haierAtNec04() {
        Setup s = new Setup(db, "Haier");
        while (!Arrays.equals(vector("nec04_volume_up"), s.table().get(24))) assertTrue(s.next());
        return s;
    }

    @Test
    public void theFirstTableIsWholeAndInKeyOrder() {
        Setup s = new Setup(db, "Samsung");
        assertEquals(Setup.Step.VOLUME, s.step());
        assertEquals(1, s.attempt());
        SortedMap<Integer, byte[]> table = s.table();
        assertArrayEquals(vector("samsung_volume_up"), table.get(24));
        assertEquals("[24, 25, 26, 164, 178, 313]", table.keySet().toString());
        assertArrayEquals(table.get(178), table.get(313));
        assertArrayEquals(new int[] {24, 25}, s.keysUnderTest());
    }

    @Test
    public void aHaierOwnerReachesTheProvenSetWithinFourTries() {
        Setup s = haierAtNec04();
        assertTrue(s.attempt() <= 4);
        assertTrue(s.ownVolumeAttempts() > 10);
        assertTrue(s.attempts() > s.ownVolumeAttempts());

        s.works();
        assertEquals(Setup.Step.MUTE, s.step());
        assertArrayEquals(vector("nec04_mute"), s.table().get(164));
        assertArrayEquals(new int[] {164}, s.keysUnderTest());
        s.works();
        assertEquals(Setup.Step.POWER, s.step());
        assertArrayEquals(vector("nec04_power"), s.table().get(26));
        s.works();
        assertEquals(Setup.Step.INPUT, s.step());
        assertArrayEquals(vector("nec04_input"), s.table().get(313));
        assertArrayEquals(new int[] {178, 313}, s.keysUnderTest());
        s.works();
        assertEquals(Setup.Step.DONE, s.step());

        SortedMap<Integer, byte[]> table = s.table();
        assertEquals("[24, 25, 26, 164, 178, 313]", table.keySet().toString());
        assertArrayEquals(vector("nec04_volume_down"), table.get(25));
        assertArrayEquals(vector("nec04_power"), table.get(26));
    }

    @Test
    public void confirmedCodesStayWhileAnotherButtonIsSearched() {
        Setup s = haierAtNec04();
        s.works();                       // volume
        s.works();                       // mute
        byte[] first = s.table().get(26);
        assertTrue(s.next());            // power: first code did not work
        SortedMap<Integer, byte[]> table = s.table();
        assertFalse(Arrays.equals(first, table.get(26)));
        assertArrayEquals(vector("nec04_volume_up"), table.get(24));
        assertArrayEquals(vector("nec04_mute"), table.get(164));
        assertEquals(2, s.attempt());
        assertTrue(s.previous());
        assertArrayEquals(first, s.table().get(26));
        assertFalse(s.previous());
    }

    @Test
    public void aSkippedButtonGetsNoCode() {
        Setup s = haierAtNec04();
        s.works();   // volume
        s.skip();    // mute
        s.works();   // power
        s.skip();    // input
        assertEquals(Setup.Step.DONE, s.step());
        assertEquals("[24, 25, 26]", s.table().keySet().toString());
    }

    @Test
    public void volumeCannotBeSkippedAndRunsOutEventually() {
        Setup s = new Setup(db, null);
        try {
            s.skip();
            assertTrue("skipped volume", false);
        } catch (IllegalStateException expected) {
            // volume is the one thing the wizard cannot do without
        }
        assertEquals(0, s.ownVolumeAttempts());
        int n = 1;
        while (s.next()) n++;
        assertEquals(s.attempts(), n);
        assertFalse(s.next());
    }

    @Test
    public void aRunSurvivesBeingSavedAndRestored() {
        Setup s = haierAtNec04();
        s.works();
        s.works();
        assertTrue(s.next());
        Setup back = Setup.restore(db, s.save());
        assertNotNull(back);
        assertEquals("Haier", back.brand());
        assertEquals(s.step(), back.step());
        assertEquals(s.attempt(), back.attempt());
        assertEquals(s.table().keySet(), back.table().keySet());
        for (int key : s.table().keySet()) assertArrayEquals(s.table().get(key), back.table().get(key));

        Setup unlisted = Setup.restore(db, new Setup(db, null).save());
        assertNotNull(unlisted);
        assertNull(unlisted.brand());
        assertNull(Setup.restore(db, "nonsense"));
        assertNull(Setup.restore(db, "MUTE\n999999\n0\n-\n-\n-\n-\n-\n"));
    }
}
