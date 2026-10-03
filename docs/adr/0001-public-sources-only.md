# 0001: Built from public sources only

Date: 2026-10-03 · Status: accepted · Principles touched: public sources, prove it on hardware

## Context

Google TV voice remotes store the infrared codes for their TV buttons, and the
box writes them over Bluetooth. On certified devices a Google system app does
this. The goal here is an app that does it on boxes without that app, and that
can be shared freely.

How the remote is programmed is public. Google publishes the reference
remote's firmware in AOSP under Apache-2.0
(`platform/hardware/telink/atv/refDesignRcu`), and the remote makers' FCC user
manuals have an "IR over BLE" chapter that names the same service and
sequence. The firmware also defines the find-my-remote service.

## Decision

- `docs/protocol.md` is written from those two sources and from our own tests
  on real hardware. It cites them and says, rule by rule, what was seen to work.
- No Google code, code database, server or branding goes into the repo or the
  app. The TV codes come from openly licensed collections (ADR 0002).
- If something can only be done with a key, signature or attestation that
  Google holds, we stop rather than work around it.
- The app is not named or presented as a Google product.

## Consequences

- The app does what the remote's published design intends, so the write-up
  rests on an openly licensed source.
- The reference firmware is not what retail remotes run. A rule counts as
  proven only once it is seen on hardware; `docs/protocol.md` tracks that.
- Android's Bluetooth stack already reserves the remote's voice service for
  privileged apps (`isRestrictedSrvcUuid` in AOSP's `GattService.java`). If a
  later release does the same to the IR service, an ordinary app can no longer
  do this.

## Revisit when

A new Android release restricts the IR service, or a retail remote behaves
differently from the reference firmware.
