#!/usr/bin/env python3
"""Tests for the committed code database (app/src/main/assets/codes.txt).

They read the file the app ships, so they hold without the sources in vendor/.
Run: python3 -m unittest discover -s tools
"""
import os
import unittest

import ircodes as ic

ASSET = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "app", "src", "main", "assets", "codes.txt")


def load():
    codes, sets, brands, popular, common = [], [], {}, [], []
    with open(ASSET, encoding="utf-8") as f:
        header = f.readline().strip()
        for line in f:
            tag, _, rest = line.rstrip("\n").partition(" ")
            if tag == "C":
                codes.append(bytes.fromhex(rest))
            elif tag == "S":
                sets.append([None if x == "-" else int(x) for x in rest.split()])
            elif tag == "B":
                name, _, indexes = rest.partition("\t")
                brands[name] = [int(x) for x in indexes.split()]
            elif tag == "P":
                popular = rest.split("\t")
            elif tag == "G":
                common = [int(x) for x in rest.split()]
    return header, codes, sets, brands, popular, common


def frame_bytes(code: bytes):
    """("nec" | "necx", four bytes) for a pulse-distance code, else None."""
    start = 6 if code[0] == 1 else 8
    hz = int.from_bytes(code[2:4], "big") * 100
    pairs = int.from_bytes(code[4:6], "big")
    t = [round(int.from_bytes(code[start + 2 * i:start + 2 * i + 2], "big") * 1e6 / hz) for i in range(2 * pairs)]
    return ic.decode_pulse_distance(t)


class Database(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.header, cls.codes, cls.sets, cls.brands, cls.popular, cls.common = load()

    def volume_up(self, set_index):
        return frame_bytes(self.codes[self.sets[set_index][0]])

    def test_header(self):
        self.assertEqual(self.header, "remotesmith-codes 1")

    def test_every_code_fits_the_remote(self):
        self.assertGreater(len(self.codes), 1000)
        self.assertEqual(len(set(self.codes)), len(self.codes))
        for code in self.codes:
            ic.check_code(code)
            self.assertLessEqual(len(code), ic.MAX_CODE_BYTES)

    def test_sets_are_whole(self):
        for s in self.sets:
            self.assertEqual(len(s), 5)
            self.assertIsNotNone(s[0])  # volume up
            self.assertIsNotNone(s[1])  # volume down
            for index in s:
                if index is not None:
                    self.assertLess(index, len(self.codes))

    def test_volume_keys_repeat_and_single_keys_do_not_double(self):
        # Held volume must keep moving; power, mute and input must not resend a whole frame.
        for s in self.sets:
            for index in s[:2]:
                self.assertIn(self.codes[index][0], (2, 3, 4))
            for index in s[2:]:
                if index is not None:
                    self.assertIn(self.codes[index][0], (1, 3, 4))

    def test_brands_and_lists_point_at_sets(self):
        self.assertGreater(len(self.brands), 200)
        for name, indexes in self.brands.items():
            self.assertTrue(name.strip() and "\t" not in name)
            self.assertTrue(indexes)
            self.assertEqual(len(set(indexes)), len(indexes))
            for i in indexes:
                self.assertLess(i, len(self.sets))
        for i in self.common:
            self.assertLess(i, len(self.sets))
        for name in self.popular:
            self.assertIn(name, self.brands)

    def test_unlisted_order_covers_every_volume_pair_once(self):
        pairs = [tuple(self.sets[i][:2]) for i in self.common]
        self.assertEqual(len(set(pairs)), len(pairs))
        self.assertEqual(set(pairs), {tuple(s[:2]) for s in self.sets})

    def test_well_known_brands_lead_with_their_own_codes(self):
        self.assertEqual(self.volume_up(self.brands["Samsung"][0]), ("necx", bytes.fromhex("070707f8")))
        self.assertEqual(self.volume_up(self.brands["LG"][0]), ("nec", bytes.fromhex("04fb02fd")))
        self.assertEqual(self.volume_up(self.brands["Vizio"][0]), ("nec", bytes.fromhex("04fb02fd")))

    def test_the_set_proven_on_a_haier_is_reachable_early(self):
        # Verified on a Haier 43UG2500A: NEC address 0x04, vol+ 0x02, vol- 0x03, mute 0x09, power 0x08, input 0x0B.
        nec = lambda hexes: ("nec", bytes.fromhex(hexes))  # noqa: E731
        early = [i for i in self.brands["Haier"][:4] if self.volume_up(i) == nec("04fb02fd")]
        self.assertTrue(early, "the NEC 04 volume pair is not among Haier's first four candidates")
        keys = lambda i: [None if k is None else frame_bytes(self.codes[k]) for k in self.sets[i]]  # noqa: E731
        up, down, mute, power, _ = keys(early[0])
        self.assertEqual((down, mute, power), (nec("04fb03fc"), nec("04fb09f6"), nec("04fb08f7")))
        # Input is found among the sets that share the confirmed volume pair, and the
        # set tried for unlisted TVs is the whole proven one.
        same_pair = [i for i, s in enumerate(self.sets) if s[:2] == self.sets[early[0]][:2]]
        self.assertIn(nec("04fb0bf4"), [keys(i)[4] for i in same_pair])
        whole = [i for i in self.common[:4] if self.volume_up(i) == nec("04fb02fd")]
        self.assertEqual(keys(whole[0]), [nec("04fb02fd"), nec("04fb03fc"), nec("04fb09f6"), nec("04fb08f7"), nec("04fb0bf4")])
        # and it is byte for byte the code the remote was programmed with
        self.assertEqual(self.codes[self.sets[whole[0]][3]], ic.code_nec(bytes.fromhex("04fb08f7")))

    def test_no_placeholder_keys(self):
        for code in self.codes:
            decoded = frame_bytes(code)
            if decoded:
                self.assertNotEqual(decoded[1], bytes.fromhex("000000ff"))

    def test_unlisted_tvs_start_with_the_big_three_and_roku(self):
        first = [self.volume_up(i) for i in self.common[:4]]
        self.assertIn(("necx", bytes.fromhex("070707f8")), first)
        self.assertIn(("nec", bytes.fromhex("04fb02fd")), first)
        self.assertIn(("nec", bytes.fromhex("eac70ff0")), first)


if __name__ == "__main__":
    unittest.main()
