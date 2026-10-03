#!/usr/bin/env python3
"""Tests for tools/ircodes.py. Run: python3 -m unittest discover -s tools"""
import json
import os
import unittest

import ircodes as ic

HERE = os.path.dirname(os.path.abspath(__file__))


def vectors():
    with open(os.path.join(HERE, "testdata", "irp_vectors.json")) as f:
        return json.load(f)["vectors"]


class ReferenceVectors(unittest.TestCase):
    """Our decoders must read what the reference renderer writes."""

    def test_nec_family(self):
        seen = 0
        for v in vectors():
            p = v["params"]
            _, once, repeat = ic.pronto_timings(v["pronto"])
            if v["protocol"] == "NEC1":
                s = p.get("S", 255 - p["D"])
                want = ("nec", bytes([p["D"], s, p["F"], p["F"] ^ 0xFF]))
                self.assertEqual(ic.decode_pulse_distance(once), want, v)
            elif v["protocol"] == "NECx2":
                want = ("necx", bytes([p["D"], p["S"], p["F"], p["F"] ^ 0xFF]))
                self.assertEqual(ic.decode_pulse_distance(repeat), want, v)
            else:
                continue
            seen += 1
        self.assertGreaterEqual(seen, 6)

    def test_rc5(self):
        seen = 0
        for v in vectors():
            if v["protocol"] != "RC5":
                continue
            p = v["params"]
            _, _, repeat = ic.pronto_timings(v["pronto"])
            self.assertEqual(ic.decode_rc5(repeat), (p["D"], p["F"], p["T"]), v)
            # and our own frame has the same shape as the reference one
            ours = ic.rc5_frame(p["D"], p["F"], p["T"])
            self.assertEqual(len(ours), len(repeat), v)
            for a, b in zip(ours[:-1], repeat[:-1]):
                self.assertEqual(round(a / ic.RC5_UNIT), round(b / ic.RC5_UNIT), v)
            self.assertAlmostEqual(sum(ours), 114000, delta=1)
            seen += 1
        self.assertGreaterEqual(seen, 5)


class RoundTrips(unittest.TestCase):
    def test_nec_bytes(self):
        for data in (bytes([0x04, 0xFB, 0x08, 0xF7]), bytes([0xEA, 0xC7, 0x17, 0xE8]), bytes([0, 0, 0, 0]), bytes([255] * 4)):
            self.assertEqual(ic.decode_pulse_distance(ic.nec_frame_bytes(data)), ("nec", data))
            self.assertEqual(ic.decode_pulse_distance(ic.necx_frame_bytes(data)), ("necx", data))

    def test_frames_last_108_ms(self):
        self.assertEqual(sum(ic.nec_frame(0x04, 0x08)), 108000)
        self.assertEqual(sum(ic.NEC_DITTO), 108000)

    def test_rc5_all_commands(self):
        for address in (0, 1, 5, 31):
            for command in (0, 12, 16, 56, 63, 64, 70, 127):
                for toggle in (0, 1):
                    self.assertEqual(ic.decode_rc5(ic.rc5_frame(address, command, toggle)), (address, command, toggle))

    def test_longer_protocol_with_nec_leader_is_not_nec(self):
        frame = ic.nec_frame(0x04, 0x08)
        longer = frame[:66] + [560, 560] * 10 + [560, 40000]
        self.assertIsNone(ic.decode_pulse_distance(longer))


class RemoteFormat(unittest.TestCase):
    def test_power_code_that_worked_on_the_haier(self):
        # First bytes of the code the remote accepted and the TV answered to (2026-10-03).
        code = ic.code_nec(bytes([0x04, 0xFB, 0x08, 0xF7]))
        self.assertTrue(code.hex().startswith("0321017c00220002015600ab001500150015001500150040"))
        self.assertEqual(len(code), 152)
        self.assertEqual(code, ic.encode_for_remote(ic.nec(0x04, 0x08)))

    def test_forms(self):
        frame = ic.nec_frame(0x04, 0x02)
        self.assertEqual(ic.code_once(38000, frame)[0], 1)
        self.assertEqual(ic.code_repeating(38000, frame)[0], 2)
        self.assertEqual(ic.code_once_then(38000, frame, ic.NEC_DITTO)[0], 3)
        rc5 = ic.code_rc5(0, 12)
        self.assertEqual(rc5[0], 4)
        self.assertEqual(int.from_bytes(rc5[2:4], "big"), 360)  # 36 kHz

    def test_repeat_only_carries_its_period(self):
        frame = ic.nec_frame(0x04, 0x02)
        code = ic.code_repeating(38000, frame)
        pairs = int.from_bytes(code[4:6], "big")
        period = int.from_bytes(code[6:8], "big")
        durations = [int.from_bytes(code[8 + 2 * i:10 + 2 * i], "big") for i in range(2 * pairs)]
        self.assertEqual(period, sum(durations))

    def test_limits(self):
        frame = ic.nec_frame(0x04, 0x02)
        self.assertEqual(len(ic.code_once_then(38000, frame + frame, ic.NEC_DITTO)), 288)
        with self.assertRaises(ValueError):
            ic.code_once(38000, frame + frame + frame)
        with self.assertRaises(ValueError):
            ic.check_code(bytes([1, 33, 0, 0, 0, 1, 0, 1, 0, 1]))  # zero carrier
        with self.assertRaises(ValueError):
            ic.check_code(bytes([7, 33, 1, 124, 0, 0]))           # unknown form
        with self.assertRaises(ValueError):
            ic.check_code(bytes([1, 33, 1, 124, 0, 2, 0, 1, 0, 1]))  # header says more pairs


class SharedVectors(unittest.TestCase):
    def test_committed_vectors_are_current(self):
        # Regenerate with: python3 tools/ircodes.py vectors > tools/testdata/codec_vectors.txt
        with open(os.path.join(HERE, "testdata", "codec_vectors.txt")) as f:
            self.assertEqual(f.read(), ic.codec_vectors())


class Captures(unittest.TestCase):
    def test_single_frame_gets_a_gap(self):
        frame = ic.nec_frame(0x04, 0x02)[:-1]  # as captured: ends on the stop mark
        press = ic.press_sequence(frame)
        self.assertEqual(len(press) % 2, 0)
        self.assertEqual(sum(press), 108000)

    def test_short_final_space_is_stretched(self):
        press = ic.press_sequence([9000, 4500, 560, 560, 560, 565])
        self.assertGreaterEqual(press[-1], 20000)

    def test_repeated_frames_are_trimmed_to_fit(self):
        frame = ic.nec_frame(0x04, 0x02)
        press = ic.press_sequence(frame * 4)
        self.assertEqual(press, frame * 2)

    def test_unlike_frames_that_do_not_fit_are_refused(self):
        a, b = ic.nec_frame(0x04, 0x02), ic.nec_frame(0x04, 0x03)
        self.assertIsNone(ic.press_sequence(a + b + a))


if __name__ == "__main__":
    unittest.main()
