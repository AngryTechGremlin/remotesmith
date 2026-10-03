package io.github.angrytechgremlin.remotesmith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Map;

public class IrCodecTest {
    @Test
    public void packsProntoExactlyAsTheToolsDo() throws Exception {
        Map<String, Vectors.Vector> vectors = Vectors.load();
        assertTrue(vectors.size() >= 10);
        for (Map.Entry<String, Vectors.Vector> v : vectors.entrySet()) {
            assertArrayEquals(v.getKey(), v.getValue().code, IrCodec.encode(v.getValue().pronto));
            assertNull(v.getKey(), IrCodec.problem(v.getValue().code));
        }
    }

    @Test
    public void formsFollowTheSequencesPresent() throws Exception {
        Map<String, Vectors.Vector> v = Vectors.load();
        assertEquals(3, v.get("nec04_power").code[0]);        // once + repeat
        assertEquals(2, v.get("samsung_volume_up").code[0]);  // repeat only
        assertEquals(1, v.get("once_only").code[0]);          // once only
        assertEquals(100, v.get("unmodulated").code[1]);      // duty cycle of an unmodulated code
        assertEquals(33, v.get("nec04_power").code[1]);
    }

    @Test
    public void acceptsAReadyMadeCode() throws Exception {
        byte[] code = Vectors.load().get("nec04_power").code;
        assertArrayEquals(code, IrCodec.parse("hex:" + IrCodec.hex(code)));
        assertArrayEquals(code, IrCodec.parse("  hex:" + IrCodec.hex(code) + " "));
    }

    @Test
    public void refusesWhatTheRemoteCannotHold() throws Exception {
        byte[] good = Vectors.load().get("near_the_limit").code;
        assertEquals(288, good.length);
        assertNull(IrCodec.problem(good));

        byte[] tooLong = new byte[304];
        tooLong[0] = 1;
        tooLong[1] = 33;
        tooLong[2] = 1;
        tooLong[3] = 124;
        tooLong[5] = 74;
        assertNotNull(IrCodec.problem(tooLong));

        assertNotNull("unknown form", IrCodec.problem(new byte[] {7, 33, 1, 124, 0, 0}));
        assertNotNull("header disagrees", IrCodec.problem(new byte[] {1, 33, 1, 124, 0, 2, 0, 1, 0, 1}));
        assertNotNull("zero carrier", IrCodec.problem(new byte[] {1, 33, 0, 0, 0, 1, 0, 1, 0, 1}));
        assertNotNull("zero duty", IrCodec.problem(new byte[] {1, 0, 1, 124, 0, 1, 0, 1, 0, 1}));
        assertNotNull("too short", IrCodec.problem(new byte[] {1, 33, 1}));
    }

    @Test
    public void refusesBadText() {
        for (String bad : new String[] {"", "hello", "0000 006D 0001", "5000 006D 0001 0000 0010 0010",
                "0000 0000 0001 0000 0010 0010", "0000 006D 0000 0000", "hex:zz", "hex:012"}) {
            try {
                IrCodec.parse(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused, as it should be
            }
        }
    }

    @Test
    public void keysAreWrittenInAscendingOrder() {
        int last = 0;
        for (int[] ids : IrCodec.KEYCODES.values()) {
            for (int id : ids) {
                assertTrue(id > last);
                last = id;
            }
        }
        assertEquals(313, last);
    }
}
