# How the remote is programmed

What a host (the TV box) sends over Bluetooth LE to set up the TV buttons of a Google reference
design voice remote, and to make it beep. Written in our own words from public sources and
checked on real hardware.

## Sources

- **AOSP reference remote firmware**, Apache-2.0:
  <https://android.googlesource.com/platform/hardware/telink/atv/refDesignRcu>.
  The remote's side of everything below is in `vendor/827x_ble_remote/`: `app_ir.c` (IR
  programming), `app_fmr/app_fmr.c` (find my remote), `app_att.c` (the GATT table), `rc_ir.h`
  (transmit buffer).
- **Ohsung's FCC user manuals** for the G10 and G20 reference remotes and the onn. remote
  (FCC ID OZ5C008, OZ5C009, OZ5C314), which have an "IR over BLE" chapter naming the same service,
  characteristics and sequence: <https://fccid.io/OZ5C009/User-Manual/Users-Manual-5122343>.

The reference firmware is not the firmware a retail remote ships with, so each rule below says
whether it was also seen on real hardware. "Verified" means on a Google TV Streamer voice remote
paired to a Raspberry Pi 5 running LineageOS 23.2 (Android 16), 2026-10-03.

## Transport

Plain GATT over the existing LE bond, alongside the remote's HID connection. No handshake, key or
signature is involved. Writes use the default (acknowledged) write type, one at a time. Do not
request an MTU: the remote asks for 180 by itself, and a longer value goes out as a prepared
("long") write that the remote reassembles, up to 300 bytes.

## IR configuration service `D343BFC0-5A21-4F05-BC7D-AF01F617B664`

| Characteristic | Name | Use |
|---|---|---|
| `D343BFC1-…` | program control | write `01` to begin a session, `00` to end it |
| `D343BFC2-…` | key id | write the 2-byte big-endian id of the button the next code is for |
| `D343BFC3-…` | code | write that button's IR code (format below) |
| `D343BFC4-…` | IR suppress | write a list of 2-byte key ids to send over Bluetooth instead of IR; empty = none |
| `D343BFC5-…` | key event | notify: `00` (down) or `01` (up), then the 2-byte key id |

### A programming session

```
BFC1 <- 01                 begin: wipes every slot
for each button, in ascending key id order:
    BFC2 <- key id
    BFC3 <- code
BFC1 <- 00                 end: the table is saved to flash
```

- **Begin wipes the whole table**, so a session must carry every button that should work
  afterwards. A session with no buttons clears the remote. (Firmware; clearing was accepted on
  hardware but its effect was not observed.)
- **Ascending key id order** is what the manuals describe. The reference firmware does not depend
  on it. Verified both ways.
- **A session times out** after 30 s without a write (firmware).
- **Codes survive** battery changes and re-pairing: they are stored in flash, with wear
  levelling. A factory reset of the remote (hold Mute + Select until the light is solid) erases
  them along with the pairing (manuals).

### Key ids

Android keycodes, five slots:

| Button | Id |
|---|---|
| Volume up | 24 |
| Volume down | 25 |
| Power | 26 |
| Mute | 164 |
| Input | 178, or 313 on remotes with a customizable ★ button instead of Input |

Input and ★ share one slot. A remote ignores the id it does not have without reporting an error,
so a host can write the input code under both. Verified: the Streamer remote uses 313.

### Code format

Every number is big-endian. Durations count carrier cycles, as in Pronto hex.

| Offset | Size | Meaning |
|---|---|---|
| 0 | 1 | form (below) |
| 1 | 1 | duty cycle in percent: 33 for a modulated carrier, 100 for none |
| 2 | 2 | carrier frequency in units of 100 Hz |
| 4 | … | form-specific |

| Form | Layout after the header | Played as |
|---|---|---|
| 1 | pairs, then mark/space pairs | once per press; holding does not repeat |
| 2 | pairs, period, then pairs | on press and again for as long as the button is held; period is the sum of all durations |
| 3 | pairs A, pairs B, sequence A, sequence B | A once, then B for as long as the button is held |
| 4 | pairs A, pairs B, sequence A, sequence B | A and B on alternate presses (toggle-bit protocols such as RC5) |

All four forms are verified on hardware, form 3 including hold-to-repeat.

Limits (firmware): a code is at most **300 bytes**, and a single sequence at most 75 pairs, the
size of the transmit buffer. The firmware does not check the second limit, but any code within
300 bytes stays inside it. Verified: a 280-byte code plays; a 288-byte code was accepted.

The carrier is used as a divisor by the firmware, so a host must never send zero.

### What a programmed button does

- It sends its IR code and **no Bluetooth key event**. The box no longer sees the press.
- With key event notifications enabled (write the client configuration descriptor of `BFC5`),
  the remote reports each press and release of a programmed button. Verified for ids 24, 25, 26,
  164 and 313. The firmware stores the enabled flag in flash, so do not toggle it needlessly.
- Ids on the suppress list go over Bluetooth again until the list is rewritten or the link drops
  (firmware; not tested on hardware).

## Find my remote service `18030001-5A21-4F05-BC7D-AF01F617B664`

One characteristic, `18030002-…`: read, notify, write without response. The value is three bytes:
an event, then the remaining buzz time in tenths of a second, big-endian.

- **To buzz:** enable notifications, then write `00 hi lo`. The maximum is 300 (30 s); writing a
  duration of 0 stops a running buzz.
- **Replies** arrive as notifications with the event in the first byte:

| Event | Meaning |
|---|---|
| `00` | request accepted; the duration is what the remote will play (0 = stopped) |
| `01` | finished: the time ran out |
| `02` | found: a button was pressed, which also stops the buzz |
| `03` | the request was longer than 30 s and was capped |
| `04` | battery too low to buzz |
| `FF` | invalid request |

While buzzing, the remote sends no key events and no IR (firmware). Start, finish, found and stop
are verified on hardware.

The remote must be connected to receive a request. An idle remote drops its link to save power;
the reference firmware can wake periodically to reconnect, at an interval set at the factory.

## Pairing notes

- Pairing mode: hold Back + Home until the light pulses. The window is about 85 s.
- A remote that was paired to a box and then forgotten by the box may refuse to pair again
  (connection timeouts). A factory reset of the remote fixes it. Seen on hardware.
- Reconnecting (firmware, `app_adv_direct` in `app.c`): a bonded remote advertises to its host
  only, with directed advertising. If the host paired under its fixed address, the remote calls
  that address. If the host paired under a private address, the remote calls either that
  address, which has since changed, or the host's identity through its resolving list. A host
  whose controller cannot resolve addresses answers neither. Seen on hardware: such a host never
  got the remote back after the link dropped; with the host's LE privacy off and a fresh
  pairing, the remote reconnected within two seconds, also after a reboot of the host.
