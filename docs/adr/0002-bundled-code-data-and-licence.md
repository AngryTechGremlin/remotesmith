# 0002: Bundled code data, licence and scope of the first release

Date: 2026-10-03 · Status: accepted · Principles touched: simplicity

## Context

The app must work offline, so it has to carry its own database of TV codes.
Google's code database is not public and is not ours to use. The best-known
public collections cannot be bundled either: irdb has a conditional, non-free
licence, and most of Flipper-IRDB predates its CC0 licence and has no licence
at all.

## Decision

- **Data:** the IRext offline database (MIT) is the main source, plus the
  Flipper-IRDB files contributed under CC0 (after commit 2319685), plus our
  own ordering of the most common code sets. `tools/build_db.py` builds one
  asset from pinned versions; only that asset is committed, with a NOTICE.
- **Not used:** irdb, pre-CC0 Flipper-IRDB files, GPL-licensed lists, LIRC,
  Global Caché, anything of Google's.
- **Code licence:** Apache-2.0, the same as the firmware source the protocol
  notes cite.
- **First release:** TV only. Soundbars and receivers can follow; the asset
  format does not assume a device type.
- **Name:** Remotesmith. No Google or Android TV trademark in the name.

## Consequences

- IRext's provenance is undocumented, so the asset stays replaceable: nothing
  in the app depends on where a code came from.
- Coverage is thinner than Google's. The wizard therefore has a
  brand-independent "most common sets" path and a way to use your own codes.
- IzzyOnDroid is not a channel: its
  [inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)
  rejects apps whose code was written with generative AI, and this one was
  (see the README). GitHub releases come first, F-Droid possibly later.

## Revisit when

A better-licensed source appears, IRext's licence or provenance is disputed,
or audio devices are added.
