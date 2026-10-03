#!/usr/bin/env python3
"""Build the app's bundled TV code database from openly licensed sources.

  tools/build_db.py            # download what is missing into vendor/, write the asset
  tools/build_db.py --report   # also print how well some well-known brands come out

Sources (docs/adr/0002):
  * IRext offline database, MIT: every TV remote with its decoded key timings.
  * Flipper-IRDB, only the TV files added after the repository adopted CC0.

Each remote becomes a "set": one code for each of volume up, volume down, mute,
power and input, already packed in the remote's format (docs/protocol.md). The
asset lists unique codes, the sets built from them, each brand's sets in the
order worth trying, and a brand-independent order for TVs that are not listed.

This is a maintainer tool. Its output is committed; the sources are not.
"""
from __future__ import annotations

import collections
import hashlib
import os
import re
import sqlite3
import subprocess
import sys
import urllib.request

import ircodes as ic

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, "vendor")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "codes.txt")

IREXT_COMMIT = "fb3b471d73e118463cf609e096188f837fa8d684"
IREXT_FILE = "db/irext_db_20260929_sqlite3.db"
IREXT_SHA256 = "fc9507bb6a0bd65fb7d407b7a65287e66ccaa00f80067ae9cc7a425cdfaca571"
IREXT_DB = os.path.join(VENDOR, "irext", os.path.basename(IREXT_FILE))

FLIPPER_REPO = "https://github.com/Lucaslhm/Flipper-IRDB.git"
FLIPPER_COMMIT = "d126fb1b6f1e114c52b4a8c19839ea65e3a9c24d"
# The commit that added the CC0 licence; only files added after it are covered.
FLIPPER_CC0_SINCE = "2319685f2cbf0cd3f809609622cade14d24fb819"
FLIPPER_DIR = os.path.join(VENDOR, "flipper-irdb")

FUNCTIONS = ["volume_up", "volume_down", "mute", "power", "input"]
# Volume keys repeat while held. The others are sent once per press unless their
# protocol has a repeat that cannot be mistaken for a second press.
HELD = {"volume_up", "volume_down"}

# Shown first in the app's brand list, in this order.
POPULAR = ["Samsung", "LG", "Sony", "TCL", "Hisense", "Vizio", "Philips", "Panasonic", "Sharp",
           "Toshiba", "Haier", "Xiaomi", "Skyworth", "Hitachi", "JVC", "Sanyo", "Insignia",
           "Westinghouse"]

# IRext names protocols after the encoder chip. Carrier by name prefix, from the
# chip's protocol as IrpProtocols.xml gives it; anything else is 38 kHz.
CARRIER = [
    ("saa3010", 36000),         # RC5
    ("philips_rc6", 36000),     # RC6
    ("upd6124", 40000),         # Sony
    ("pioneer", 40000),
    ("m50462", 32600),          # Mitsubishi
    ("mn6014", 57600),          # Panasonic_Old
    ("lc7464m", 37000),         # Panasonic
    ("rca_56k", 56000),
    ("gemini-c10-31_36k", 31360),
]

IREXT_KEYS = {0: "power", 1: "mute", 7: "volume_up", 8: "volume_down", 10: "input"}

FLIPPER_NAMES = {
    "power": "power", "standby": "power",
    "vol_up": "volume_up", "vol+": "volume_up",
    "vol_dn": "volume_down", "vol_down": "volume_down", "vol-": "volume_down",
    "mute": "mute",
    "source": "input", "input": "input", "av": "input", "sources": "input",
}

BRAND_FIXES = {
    "kongka": "Konka", "chang hong": "Changhong", "emeson": "Emerson", "deawoo": "Daewoo",
    "optpma": "Optoma", "nsignia": "Insignia", "dinex": "Dynex", "goldstar": "GoldStar",
    "benq": "BenQ", "viewsonic": "ViewSonic", "letv": "LeTV", "chimei": "Chimei",
    "continentaledison": "Continental Edison", "durabrand du-1301": "DuraBrand", "hanns.g": "HANNS.G",
}


# --- fetching -----------------------------------------------------------------

def fetch_irext() -> None:
    if not os.path.exists(IREXT_DB):
        os.makedirs(os.path.dirname(IREXT_DB), exist_ok=True)
        url = f"https://media.githubusercontent.com/media/irext/database/{IREXT_COMMIT}/{IREXT_FILE}"
        print("downloading", url, file=sys.stderr)
        urllib.request.urlretrieve(url, IREXT_DB)
    digest = hashlib.sha256()
    with open(IREXT_DB, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    if digest.hexdigest() != IREXT_SHA256:
        raise SystemExit(f"{IREXT_DB} is not the pinned IRext database (sha256 {digest.hexdigest()})")


def _git(*args: str) -> str:
    return subprocess.run(["git", "-C", FLIPPER_DIR, *args], check=True, capture_output=True, text=True).stdout


def fetch_flipper() -> dict[str, str]:
    """{path: file text} for every TV file added after the CC0 licence commit."""
    if not os.path.isdir(os.path.join(FLIPPER_DIR, ".git")):
        os.makedirs(VENDOR, exist_ok=True)
        print("cloning", FLIPPER_REPO, file=sys.stderr)
        subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout", FLIPPER_REPO, FLIPPER_DIR], check=True)
    paths = _git("diff", "--diff-filter=A", "--name-only", FLIPPER_CC0_SINCE, FLIPPER_COMMIT, "--", "TVs").splitlines()
    return {p: _git("show", f"{FLIPPER_COMMIT}:{p}") for p in paths}


# --- signals ------------------------------------------------------------------
#
# A signal is what a key sends, in the most exact form we can name:
#   ("nec", four bytes)       the original remote's frame and short repeat
#   ("necx", four bytes)      the 4.5 ms leader variant; a held key resends the frame
#   ("rc5", address, command) both toggle states
#   ("raw", carrier, timings) a capture, sent as it was captured

def carrier_for(label: str) -> int:
    for prefix, hz in CARRIER:
        if label.startswith(prefix):
            return hz
    return 38000


def signal_from_capture(label: str, t: list[int]):
    label = label.lower()
    first = ic.split_frames(t)[0] if t else []
    if label.startswith(("upd6121g", "tc9012")):
        decoded = ic.decode_pulse_distance(first)
        if decoded:
            return decoded
    if label.startswith("saa3010"):
        decoded = ic.decode_rc5(first)
        if decoded:
            return ("rc5", decoded[0], decoded[1])
    press = ic.press_sequence(t)
    if press is None:
        return None
    # Captures of one signal differ by a few microseconds; snap them together.
    snapped = tuple(max(50, round(d / 50) * 50) for d in press[:-1]) + (round(press[-1] / 1000) * 1000,)
    return ("raw", carrier_for(label), snapped)


def code_for(signal, function: str) -> bytes:
    held = function in HELD
    kind = signal[0]
    if kind == "nec":
        return ic.code_nec(signal[1])
    if kind == "rc5":
        return ic.code_rc5(signal[1], signal[2])
    if kind == "necx":
        hz, t = 38000, ic.necx_frame_bytes(signal[1])
    else:
        hz, t = signal[1], list(signal[2])
    return ic.code_repeating(hz, t) if held else ic.code_once(hz, t)


# --- sources ------------------------------------------------------------------

def brand_name(raw: str) -> str:
    name = re.sub(r"\s+", " ", raw.replace("_", " ")).strip()
    if name.lower() in BRAND_FIXES:
        return BRAND_FIXES[name.lower()]
    if (name.isupper() and len(name) > 4) or name.islower():
        name = name.title()
    return name


class Stats(collections.Counter):
    def note(self, what: str) -> None:
        self[what] += 1


def load_irext(stats: Stats) -> list[tuple[str, dict]]:
    """[(brand, {function: signal})] in the order IRext ranks them within each brand."""
    db = sqlite3.connect(f"file:{IREXT_DB}?mode=ro", uri=True)
    rows = db.execute("""
        select r.id, r.priority, r.protocol, b.name_en, d.key_number, d.key_value
        from remote_index r
        join brand b on b.id = r.brand_id
        join decode_remote d on d.remote_index_id = r.id
        where r.category_id = 2 and r.status = 1 and d.key_number in (0, 1, 7, 8, 10)
        order by r.priority, r.id""").fetchall()
    remotes: dict[int, tuple[str, dict]] = {}
    for rid, _, label, brand, key, value in rows:
        entry = remotes.setdefault(rid, (brand_name(brand), {}))
        t = [int(x) for x in (value or "").split(",") if x.strip()]
        if len(t) < 4:
            continue
        signal = signal_from_capture(label, t)
        if signal is None:
            stats.note("irext keys that do not fit the remote")
            continue
        entry[1][IREXT_KEYS[key]] = signal
    for _, signals in remotes.values():
        drop_placeholders(signals, stats)
    stats["irext remotes"] = len(remotes)
    return list(remotes.values())


# What IRext stores for a key the original remote does not have.
PLACEHOLDERS = {bytes.fromhex("000000ff"), bytes(4)}


def drop_placeholders(signals: dict, stats: Stats) -> None:
    up = signals.get("volume_up")
    for function in list(signals):
        s = signals[function]
        if s[0] in ("nec", "necx") and s[1] in PLACEHOLDERS and not (up and up[:1] == s[:1] and up[1][:2] == s[1][:2]):
            del signals[function]
            stats.note("irext placeholder keys dropped")


def parse_flipper(text: str) -> dict:
    """One .ir file -> {function: signal} for the keys we use."""
    signals, cur = [], {}
    for line in text.splitlines():
        if line.startswith("name:"):
            if cur:
                signals.append(cur)
            cur = {"name": line.split(":", 1)[1].strip()}
        elif ":" in line and cur:
            k, v = line.split(":", 1)
            cur[k.strip()] = v.strip()
    if cur:
        signals.append(cur)
    out = {}
    for s in signals:
        function = FLIPPER_NAMES.get(s["name"].lower())
        if function is None or function in out:
            continue
        if s.get("type") == "parsed":
            a = bytes.fromhex(s["address"].replace(" ", ""))
            c = bytes.fromhex(s["command"].replace(" ", ""))
            protocol = s.get("protocol")
            if protocol == "NEC":
                out[function] = ("nec", bytes([a[0], a[0] ^ 0xFF, c[0], c[0] ^ 0xFF]))
            elif protocol == "NECext":
                out[function] = ("nec", bytes([a[0], a[1], c[0], c[1]]))
            elif protocol == "Samsung32":
                out[function] = ("necx", bytes([a[0], a[0], c[0], c[0] ^ 0xFF]))
            elif protocol == "RC5":
                out[function] = ("rc5", a[0], c[0])
            elif protocol == "RC5X":
                out[function] = ("rc5", a[0], c[0] | 0x40)
        elif s.get("type") == "raw":
            t = [int(x) for x in s.get("data", "").split()]
            decoded = ic.decode_pulse_distance(ic.split_frames(t)[0]) if t else None
            if decoded:
                out[function] = decoded
            else:
                press = ic.press_sequence(t)
                if press:
                    out[function] = ("raw", int(s.get("frequency", "38000")), tuple(press))
    return out


def load_flipper(files: dict[str, str], stats: Stats) -> list[tuple[str, dict]]:
    remotes = []
    for path in sorted(files):
        parts = path.split("/")
        if parts[1] == "Hotels":
            continue  # a hotel's own remote, not a TV brand
        brand = brand_name(parts[1] if len(parts) > 2 else os.path.splitext(parts[1])[0])
        remotes.append((brand, parse_flipper(files[path])))
    stats["flipper remotes"] = len(remotes)
    return remotes


# --- building -----------------------------------------------------------------

# A set nobody has listed under a brand but that a great many TVs answer to. It is
# tried early for unlisted TVs: Roku TVs are sold under many brand names.
EARLY = [("nec", bytes.fromhex("eac7"))]

# Weight of breadth of use against a brand's own remotes, in remotes (see build()).
PRIOR = 20


def build(remotes: list[tuple[str, dict]], stats: Stats):
    """-> (codes, sets, {brand: [set index]}, [set index] for unlisted TVs)"""
    codes: dict[bytes, int] = {}
    sets: dict[tuple, int] = {}
    seen: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)  # brand -> set -> remotes
    early = set()

    def code_index(code: bytes) -> int:
        return codes.setdefault(code, len(codes))

    for brand, signals in remotes:
        if "volume_up" not in signals or "volume_down" not in signals:
            stats.note("remotes without both volume keys")
            continue
        try:
            key = tuple(code_index(code_for(signals[f], f)) if f in signals else None for f in FUNCTIONS)
        except ValueError:
            stats.note("remotes with a code the remote cannot hold")
            continue
        index = sets.setdefault(key, len(sets))
        seen[brand][index] += 1
        up = signals["volume_up"]
        if (up[0], up[1][:2] if isinstance(up[1], bytes) else None) in EARLY:
            early.add(key[:2])

    set_list = list(sets)
    volume = lambda i: set_list[i][:2]  # noqa: E731  (up, down): what the wizard tests first

    # Per volume pair: which brands use it, how many of a brand's remotes do, and the
    # fullest set that carries it.
    users = collections.defaultdict(set)
    within = collections.defaultdict(collections.Counter)
    fullest = {}
    for brand, counts in seen.items():
        for i, n in counts.items():
            users[volume(i)].add(brand)
            within[brand][volume(i)] += n
    for i, key in enumerate(set_list):
        best = fullest.get(volume(i))
        if best is None or sum(k is not None for k in key) > sum(k is not None for k in set_list[best]):
            fullest[volume(i)] = i

    # How likely a pair is before knowing the brand: its share of all brands, as if every
    # brand had PRIOR more remotes spread that way. The sources are thin for models sold
    # outside China, so this keeps a widely used set from sinking below obscure ones.
    def prior(pair) -> float:
        return PRIOR * len(users[pair]) / len(seen)

    def brand_order(brand: str, bonus) -> list[int]:
        counts = seen[brand]
        first_seen = {i: n for n, i in enumerate(counts)}
        return sorted(counts, key=lambda i: (
            -(within[brand][volume(i)] + prior(volume(i)) + bonus(volume(i))), -counts[i], first_seen[i]))

    # Unlisted TVs: each popular brand's first choice, the sets in EARLY, then by breadth of use.
    order = []
    for brand in POPULAR:
        if brand in seen:
            pair = volume(brand_order(brand, lambda _: 0)[0])
            if pair not in order:
                order.append(pair)
    leaders = len(order)
    for pair in sorted(early, key=lambda p: fullest[p]):
        if pair not in order:
            order.insert(min(3, len(order)), pair)
    order += [p for p in sorted(users, key=lambda p: (-len(users[p]), fullest[p])) if p not in order]
    common = [fullest[pair] for pair in order]

    # Many TVs are built on a major brand's platform and answer to its codes, so the
    # leaders of that list get a head start inside every brand that lists them at all.
    rank = {pair: n for n, pair in enumerate(order)}
    brands = {brand: brand_order(brand, lambda p: 4 if rank[p] < 4 else 2 if rank[p] < leaders else 0)
              for brand in seen}

    stats["codes"], stats["sets"], stats["brands"] = len(codes), len(set_list), len(brands)
    return list(codes), set_list, brands, common


def write_asset(codes, sets, brands, common) -> int:
    lines = ["remotesmith-codes 1",
             f"# IRext database {IREXT_COMMIT[:7]} (MIT) and Flipper-IRDB {FLIPPER_COMMIT[:7]} (CC0 files). See NOTICE."]
    lines += ["C " + code.hex() for code in codes]
    lines += ["S " + " ".join("-" if k is None else str(k) for k in key) for key in sets]
    for brand in sorted(brands, key=str.lower):
        lines.append("B " + brand + "\t" + " ".join(map(str, brands[brand])))
    lines.append("P " + "\t".join(b for b in POPULAR if b in brands))
    lines.append("G " + " ".join(map(str, common)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    data = ("\n".join(lines) + "\n").encode()
    with open(OUT, "wb") as f:
        f.write(data)
    return len(data)


def describe(code: bytes) -> str:
    """A short human label for a code, for the report only."""
    form, pairs = code[0], int.from_bytes(code[4:6], "big")
    start = 8 if form != 1 else 6
    t = [int.from_bytes(code[start + 2 * i:start + 2 * i + 2], "big") * 1e6 / (int.from_bytes(code[2:4], "big") * 100)
         for i in range(2 * pairs)]
    decoded = ic.decode_pulse_distance([round(x) for x in t])
    if decoded:
        return f"{decoded[0]} {decoded[1].hex()}"
    return f"form {form}, {len(code)} bytes"


def main(argv: list[str]) -> int:
    stats = Stats()
    fetch_irext()
    remotes = load_irext(stats) + load_flipper(fetch_flipper(), stats)
    codes, sets, brands, common = build(remotes, stats)
    size = write_asset(codes, sets, brands, common)
    for what, n in sorted(stats.items()):
        print(f"{n:6d}  {what}")
    print(f"{size:6d}  bytes written to {os.path.relpath(OUT, ROOT)}")
    if "--report" in argv:
        for brand in POPULAR:
            if brand in brands:
                pairs = list(dict.fromkeys(sets[i][:2] for i in brands[brand]))
                print(f"{brand:12s} {len(brands[brand]):3d} sets, {len(pairs):3d} volume pairs; first: "
                      + "; ".join(describe(codes[p[0]]) for p in pairs[:6]))
        print("unlisted TVs, first 14:", "; ".join(describe(codes[sets[i][0]]) for i in common[:14]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
