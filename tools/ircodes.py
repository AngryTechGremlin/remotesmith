#!/usr/bin/env python3
"""IR code toolbox: protocols <-> timings <-> Pronto <-> the remote's code format.

Written from docs/protocol.md. Timings are lists of microseconds that alternate
mark, space, mark, space… and start with a mark.

  ircodes.py samsung 0x07 0x02         # Samsung32 device/command -> Pronto
  ircodes.py nec 0x04 0x08             # NEC address/command -> Pronto
  ircodes.py broadlink <hex|b64>       # learned Broadlink packet -> Pronto
  ircodes.py encode "0000 006D ..."    # Pronto -> remote code (hex)
  ircodes.py profile codes.json        # {"power": "<pronto>", ...} -> keyed remote codes
  ircodes.py testforms <dir>           # profiles that exercise each code form on a NEC 0x04 TV
  ircodes.py vectors                   # the vectors the app's tests check its encoder against
"""
from __future__ import annotations

import base64
import json
import os
import sys

# Android keycodes the remote's IR keys are addressed by, in the ascending
# order they are written. Input goes to both ids: a remote has either an Input
# button (178) or a customizable star button (313) and ignores the other.
KEYCODES = {
    "volume_up": [24],
    "volume_down": [25],
    "power": [26],
    "mute": [164],
    "input": [178, 313],
}

PRONTO_UNIT = 0.241246  # microseconds per Pronto frequency-word unit
MAX_CODE_BYTES = 300    # the remote keeps at most this much per button
MAX_PAIRS = 73          # mark/space pairs that fit in MAX_CODE_BYTES


# --- Pronto -------------------------------------------------------------------

def pronto_from_timings(freq_hz: float, once_us: list[int], repeat_us: list[int]) -> str:
    """Build a modulated (0000) Pronto string from mark/space durations in µs."""
    if len(once_us) % 2 or len(repeat_us) % 2:
        raise ValueError("durations must come in mark/space pairs")
    fword = round(1e6 / (freq_hz * PRONTO_UNIT))
    period_us = fword * PRONTO_UNIT

    def cycles(us: int) -> int:
        return min(0xFFFF, max(1, round(us / period_us)))

    words = [0, fword, len(once_us) // 2, len(repeat_us) // 2]
    words += [cycles(d) for d in once_us] + [cycles(d) for d in repeat_us]
    return " ".join(f"{w:04X}" for w in words)


def parse_pronto(pronto: str) -> tuple[int, int, list[int], list[int]]:
    words = [int(w, 16) for w in pronto.split()]
    kind, fword, once, rpt = words[:4]
    if kind not in (0x0000, 0x0100):
        raise ValueError(f"only raw Pronto (0000/0100) is supported, got {kind:04X}")
    body = words[4:]
    if len(body) != 2 * (once + rpt):
        raise ValueError(f"Pronto says {once}+{rpt} pairs but carries {len(body)} words")
    return kind, fword, body[: 2 * once], body[2 * once :]


def pronto_timings(pronto: str) -> tuple[float, list[int], list[int]]:
    """Pronto -> (carrier Hz, once µs, repeat µs)."""
    _, fword, once, rpt = parse_pronto(pronto)
    period_us = fword * PRONTO_UNIT
    return 1e6 / period_us, [round(w * period_us) for w in once], [round(w * period_us) for w in rpt]


# --- NEC and its Samsung-style variant ---------------------------------------
#
# Both carry 32 bits, least significant bit first, as a 560 µs mark followed by a
# short (0) or long (1) space. They differ in the leader and in how a held key
# repeats: NEC sends a short "ditto" frame, the variant resends the whole frame.

def _pulse_distance(lead: tuple[int, int], unit: int, bits: list[int], gap_to_us: int) -> list[int]:
    """Pulse-distance frame: leader, 1 = unit/3*unit, 0 = unit/unit, stop bit, gap."""
    t = [lead[0], lead[1]]
    for b in bits:
        t += [unit, 3 * unit if b else unit]
    t += [unit]
    t += [max(unit, gap_to_us - sum(t))]
    return t


def _bits_lsb(*bytes_: int) -> list[int]:
    return [(byte >> i) & 1 for byte in bytes_ for i in range(8)]


FRAME_PERIOD = 108000

# NEC's short "still held" frame.
NEC_DITTO = [9000, 2250, 560, FRAME_PERIOD - 9000 - 2250 - 560]


def nec_frame_bytes(data: bytes) -> list[int]:
    """One NEC frame carrying these four bytes."""
    return _pulse_distance((9000, 4500), 560, _bits_lsb(*data), FRAME_PERIOD)


def nec_frame(address: int, command: int) -> list[int]:
    """One standard NEC frame: address + ~address, command + ~command."""
    return nec_frame_bytes(bytes([address, address ^ 0xFF, command, command ^ 0xFF]))


def necx_frame_bytes(data: bytes) -> list[int]:
    """One frame of the 4.5 ms leader variant (Samsung32, TC9012) carrying four bytes."""
    return _pulse_distance((4500, 4500), 560, _bits_lsb(*data), FRAME_PERIOD)


def nec(address: int, command: int) -> str:
    """NEC at 38 kHz. Once = the full frame; repeat = the short "ditto" frame."""
    return pronto_from_timings(38000, nec_frame(address, command), NEC_DITTO)


def samsung32(device: int, command: int) -> str:
    """Samsung32 at 38 kHz: the whole frame is the repeat sequence."""
    frame = necx_frame_bytes(bytes([device, device, command, command ^ 0xFF]))
    return pronto_from_timings(38000, [], frame)


def decode_pulse_distance(t: list[int]) -> tuple[str, bytes] | None:
    """A captured frame -> ("nec" | "necx", its four bytes), or None if it is neither."""
    if len(t) < 67:
        return None
    if 8000 <= t[0] <= 10000 and 3900 <= t[1] <= 5100:
        family = "nec"
    elif 3900 <= t[0] <= 5100 and 3900 <= t[1] <= 5100:
        family = "necx"
    else:
        return None
    bits = []
    for i in range(32):
        mark, space = t[2 + 2 * i], t[3 + 2 * i]
        if not 300 <= mark <= 900:
            return None
        if 300 <= space <= 900:
            bits.append(0)
        elif 1300 <= space <= 2100:
            bits.append(1)
        else:
            return None
    if not 300 <= t[66] <= 900:
        return None
    if len(t) > 67 and t[67] < 3000:
        return None  # more data bits follow: a longer protocol with the same leader
    return family, bytes(sum(bits[8 * k + i] << i for i in range(8)) for k in range(4))


# --- RC5 ----------------------------------------------------------------------
#
# 14 Manchester-coded bits at 36 kHz: start, field (inverted bit 6 of the
# command), toggle, 5 address bits, 6 command bits. The toggle bit flips on each
# new press, which is how a TV tells a second press from a held key.

RC5_UNIT = 889
RC5_PERIOD = 114000


def rc5_frame(address: int, command: int, toggle: int) -> list[int]:
    bits = [0 if command & 0x40 else 1, toggle & 1]
    bits += [(address >> i) & 1 for i in range(4, -1, -1)]
    bits += [(command >> i) & 1 for i in range(5, -1, -1)]
    levels = [1]  # second half of the start bit; its first half is a space nobody sees
    for b in bits:
        levels += [0, 1] if b else [1, 0]
    t, run = [], 1
    for prev, cur in zip(levels, levels[1:]):
        if cur == prev:
            run += 1
        else:
            t.append(run * RC5_UNIT)
            run = 1
    t.append(run * RC5_UNIT)
    if len(t) % 2:  # ended on a mark
        t.append(RC5_PERIOD - sum(t))
    else:           # ended on a space: stretch it into the gap
        t[-1] += RC5_PERIOD - sum(t)
    return t


def decode_rc5(t: list[int]) -> tuple[int, int, int] | None:
    """A captured RC5 frame -> (address, command, toggle), or None."""
    if not t:
        return None
    unit = min(t)
    if not 700 <= unit <= 1050:
        return None
    levels = [0]
    for i, d in enumerate(t):
        n = round(d / unit)
        if n > 2:
            if i % 2 == 0:
                return None  # a mark is never longer than two half-bits
            n = 1            # the gap: only its first half-bit can belong to the frame
        elif n < 1:
            return None
        levels += [1 - i % 2] * n
    if len(levels) % 2:
        levels.append(0)
    bits = []
    for a, b in zip(levels[0::2], levels[1::2]):
        if (a, b) == (0, 1):
            bits.append(1)
        elif (a, b) == (1, 0):
            bits.append(0)
        else:
            break
    if len(bits) < 14 or bits[0] != 1:
        return None
    bits = bits[:14]
    address = sum(b << i for i, b in zip(range(4, -1, -1), bits[3:8]))
    command = sum(b << i for i, b in zip(range(5, -1, -1), bits[8:14]))
    if bits[1] == 0:
        command |= 0x40
    return address, command, bits[2]


# --- captured frames ----------------------------------------------------------

def split_frames(t: list[int], gap_us: int = 10000) -> list[list[int]]:
    """Cut a mark/space list after every space of at least gap_us."""
    frames, cur = [], []
    for i, d in enumerate(t):
        cur.append(d)
        if i % 2 == 1 and d >= gap_us:
            frames.append(cur)
            cur = []
    if cur:
        frames.append(cur)
    return frames


def _similar(a: list[int], b: list[int]) -> bool:
    return len(a) == len(b) and all(abs(x - y) <= max(150, 0.2 * max(x, y)) for x, y in zip(a[:-1], b[:-1]))


def press_sequence(t: list[int], max_pairs: int = MAX_PAIRS) -> list[int] | None:
    """What a capture says to send for one key press, as whole pairs ending in a gap.

    A capture may hold one frame or the few frames its protocol needs per press.
    Returns None if that cannot be made to fit in max_pairs.
    """
    t = [d for d in t]
    if len(t) < 4:
        return None
    if len(t) % 2:
        t.append(0)  # ended on a mark: the gap is filled in below
    body = sum(t) - t[-1]
    t[-1] = max(t[-1], 20000, FRAME_PERIOD - body)
    if len(t) // 2 <= max_pairs:
        return t
    frames = split_frames(t)
    if len(frames) > 1 and all(_similar(frames[0], f) for f in frames[1:]):
        keep = []
        for f in frames:
            if (len(keep) + len(f)) // 2 > max_pairs:
                break
            keep += f
        return keep or None
    return None


# --- Broadlink ----------------------------------------------------------------

BROADLINK_TICK_US = 8192 / 269  # ≈ 30.45 µs per tick (python-broadlink's unit)


def broadlink_to_pronto(packet: bytes, freq_hz: float = 38000) -> str:
    """Learned Broadlink IR packet -> Pronto (all in the once sequence).

    Packet: 0x26 (IR), repeat count, u16 LE length, then durations in ticks;
    a duration of 0 escapes a 2-byte big-endian value. Broadlink does not record
    the carrier, so it is assumed (38 kHz fits Samsung and NEC TVs).
    """
    if packet[0] != 0x26:
        raise ValueError(f"not an IR packet (type 0x{packet[0]:02X})")
    length = int.from_bytes(packet[2:4], "little")
    data, i, durations = packet[4 : 4 + length], 0, []
    while i < len(data):
        v = data[i]
        i += 1
        if v == 0:
            if i + 2 > len(data):
                break
            v = int.from_bytes(data[i : i + 2], "big")
            i += 2
        durations.append(round(v * BROADLINK_TICK_US))
    # Trailing 0x0D 0x05 terminator and odd tails: keep whole mark/space pairs.
    while durations and durations[-1] == 0:
        durations.pop()
    if len(durations) % 2:
        durations.append(100000)  # final mark gets an inter-frame gap
    return pronto_from_timings(freq_hz, durations, [])


def _decode_packet(arg: str) -> bytes:
    try:
        return bytes.fromhex(arg)
    except ValueError:
        return base64.b64decode(arg)


# --- remote code format (docs/protocol.md, "Code format") --------------------

def _header(form: int, kind: int, fword: int) -> bytearray:
    carrier = round(1e6 / (fword * PRONTO_UNIT) / 100)  # units of 100 Hz
    duty = 100 if kind == 0x0100 else 33                # percent
    return bytearray([form, duty]) + carrier.to_bytes(2, "big")


def encode_for_remote(pronto: str) -> bytes:
    """Pronto -> form 1 (once), 2 (repeat only) or 3 (once + repeat)."""
    kind, fword, once, rpt = parse_pronto(pronto)
    n_once, n_rpt = len(once) // 2, len(rpt) // 2
    out = _header((1 if n_once else 0) | (2 if n_rpt else 0), kind, fword)
    if n_once:
        out += n_once.to_bytes(2, "big")
    if n_rpt:
        out += n_rpt.to_bytes(2, "big")
        if not n_once:
            out += (sum(rpt) & 0xFFFF).to_bytes(2, "big")  # repeat period, in carrier cycles
    for w in once + rpt:
        out += w.to_bytes(2, "big")
    return check_code(bytes(out))


def encode_alternating(pronto_a: str, pronto_b: str) -> bytes:
    """Two single-sequence Prontos -> form 4: A and B are sent on alternate presses.

    This is how toggle-bit protocols (RC5, RC6) are stored.
    """
    kind, fword, once_a, rpt_a = parse_pronto(pronto_a)
    kind_b, fword_b, once_b, rpt_b = parse_pronto(pronto_b)
    if (kind, fword) != (kind_b, fword_b):
        raise ValueError("both sequences must share one carrier")
    a, b = once_a or rpt_a, once_b or rpt_b
    out = _header(4, kind, fword)
    out += (len(a) // 2).to_bytes(2, "big") + (len(b) // 2).to_bytes(2, "big")
    for w in a + b:
        out += w.to_bytes(2, "big")
    return check_code(bytes(out))


def code_once(freq_hz: float, t: list[int]) -> bytes:
    """Form 1: sent once per press."""
    return encode_for_remote(pronto_from_timings(freq_hz, t, []))


def code_repeating(freq_hz: float, t: list[int]) -> bytes:
    """Form 2: sent on press and again for as long as the button is held."""
    return encode_for_remote(pronto_from_timings(freq_hz, [], t))


def code_once_then(freq_hz: float, once: list[int], repeat: list[int]) -> bytes:
    """Form 3: once, then the repeat sequence while held."""
    return encode_for_remote(pronto_from_timings(freq_hz, once, repeat))


def code_nec(data: bytes) -> bytes:
    """NEC as the original remote sends it: the frame, then dittos while held."""
    return code_once_then(38000, nec_frame_bytes(data), NEC_DITTO)


def code_rc5(address: int, command: int) -> bytes:
    """RC5 as the original remote sends it: the toggle bit flips on each press."""
    return encode_alternating(pronto_from_timings(36000, rc5_frame(address, command, 0), []),
                              pronto_from_timings(36000, rc5_frame(address, command, 1), []))


def check_code(code: bytes) -> bytes:
    """Raise if the remote could not store or play this code; mirrors IrCodec.problem."""
    if len(code) > MAX_CODE_BYTES:
        raise ValueError(f"code is {len(code)} bytes; the remote keeps at most {MAX_CODE_BYTES}")
    if len(code) < 6:
        raise ValueError("code is too short")
    form, duty = code[0], code[1]
    carrier = int.from_bytes(code[2:4], "big")
    n1 = int.from_bytes(code[4:6], "big")
    if form == 1:
        expected = 6 + 4 * n1
    elif form == 2:
        expected = 8 + 4 * n1
    elif form in (3, 4) and len(code) >= 8:
        expected = 8 + 4 * (n1 + int.from_bytes(code[6:8], "big"))
    else:
        raise ValueError(f"unknown code form {form}")
    if expected != len(code):
        raise ValueError(f"code is {len(code)} bytes but its header says {expected}")
    if not 1 <= duty <= 100:
        raise ValueError(f"duty cycle {duty}% is out of range")
    if not 100 <= carrier <= 5000:
        raise ValueError(f"carrier {carrier * 100} Hz is out of range")
    return code


# --- test profiles ------------------------------------------------------------

def write_test_forms(outdir: str) -> list[str]:
    """Profiles that exercise each code form, for a TV that answers to NEC address 0x04.

    Only Volume Up changes; the other buttons keep their normal codes so the
    remote stays usable during the tests.
    """
    addr, vol_up, vol_dn = 0x04, 0x02, 0x03
    base = {
        "volume_up": nec(addr, vol_up),
        "volume_down": nec(addr, vol_dn),
        "power": nec(addr, 0x08),
        "mute": nec(addr, 0x09),
        "input": nec(addr, 0x0B),
    }
    up, down = nec_frame(addr, vol_up), nec_frame(addr, vol_dn)
    tests = {
        # Tap: one step. Hold: the full frame repeats, so the volume keeps moving.
        "form2-repeat-only": {"volume_up": pronto_from_timings(38000, [], up)},
        # Tap or hold: exactly one step.
        "form1-once-only": {"volume_up": pronto_from_timings(38000, up, [])},
        # Each press of Volume Up alternates: up, down, up, down.
        "form4-alternating": {"volume_up": "hex:" + encode_alternating(
            pronto_from_timings(38000, up, []), pronto_from_timings(38000, down, [])).hex()},
        # 288 bytes, near the 300-byte limit: one tap sends the frame twice, so two steps.
        "long-code": {"volume_up": pronto_from_timings(38000, up + up, NEC_DITTO)},
    }
    os.makedirs(outdir, exist_ok=True)
    written = []
    for name, change in {"baseline": {}, **tests}.items():
        profile = {**base, **change}
        for value in profile.values():
            if not value.startswith("hex:"):
                encode_for_remote(value)  # raises if the remote could not take it
        path = os.path.join(outdir, name + ".json")
        with open(path, "w") as f:
            json.dump(profile, f, indent=2)
        written.append(path)
    return written


# --- vectors shared with the app's tests --------------------------------------

def codec_vectors() -> str:
    """Named Pronto codes and the remote code each packs into, one per line.

    The app's IrCodec must produce the same bytes (tools/testdata/codec_vectors.txt).
    """
    nec04 = {"volume_up": 0x02, "volume_down": 0x03, "mute": 0x09, "power": 0x08, "input": 0x0B}
    frame = nec_frame(0x04, 0x02)
    prontos = {f"nec04_{name}": nec(0x04, command) for name, command in nec04.items()}
    prontos.update({
        "samsung_volume_up": samsung32(0x07, 0x07),
        "samsung_power_once": pronto_from_timings(38000, necx_frame_bytes(bytes([7, 7, 2, 0xFD])), []),
        "once_only": pronto_from_timings(38000, frame, []),
        "near_the_limit": pronto_from_timings(38000, frame + frame, NEC_DITTO),
        "rc5_36khz_once": pronto_from_timings(36000, rc5_frame(0, 12, 0), []),
        "unmodulated": "0100 006D 0002 0000 0010 0020 0010 0400",
    })
    return "".join(f"{name}\t{p}\t{encode_for_remote(p).hex()}\n" for name, p in prontos.items())


# --- CLI ----------------------------------------------------------------------

def _int(s: str) -> int:
    return int(s, 0)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[1], argv[2:]
    if cmd == "samsung":
        print(samsung32(_int(args[0]), _int(args[1])))
    elif cmd == "nec":
        print(nec(_int(args[0]), _int(args[1])))
    elif cmd == "broadlink":
        print(broadlink_to_pronto(_decode_packet(args[0])))
    elif cmd == "encode":
        print(encode_for_remote(args[0]).hex())
    elif cmd == "profile":
        with open(args[0]) as f:
            codes = json.load(f)
        out = {name: {"keycodes": KEYCODES[name], "code": encode_for_remote(p).hex()} for name, p in codes.items()}
        print(json.dumps(out, indent=2))
    elif cmd == "testforms":
        for path in write_test_forms(args[0]):
            print(path)
    elif cmd == "vectors":
        sys.stdout.write(codec_vectors())
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
