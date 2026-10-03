# Remotesmith

Remotesmith is an Android TV app that sets up the **Volume, Mute, Power and Input** buttons of a
Google TV voice remote so they control your TV, and makes the remote **beep when it is lost**.

On a certified Google TV device, Google's own software does this. On a box without it, such as a
Raspberry Pi or another device running LineageOS, those buttons do nothing useful. Remotesmith
fills that gap.

> [!WARNING]
> **Experimental software. Using it is your choice and at your own risk.** It writes to your
> remote: setting up the TV buttons overwrites whatever TV codes the remote has stored, and they
> cannot be read back first. It could also affect a remote in ways not seen in testing, which
> covered one box, one TV and two remotes of one model. It is provided "as is", without warranty
> of any kind, and the authors take no responsibility for any effect on your remote, your TV or
> anything else. The [licence](LICENSE) has the full terms (sections 7 and 8).

Remotesmith is not affiliated with or endorsed by Google. Google TV and Chromecast are trademarks
of Google LLC; other names belong to their owners.

## What you need

- An Android TV box running **Android 13 or newer** that does not have Google's own remote setup.
- A remote built on Google's reference design, **paired to the box over Bluetooth**. Tested with
  the Google TV Streamer voice remote. The Chromecast with Google TV remote and the G10/G20
  reference remotes use the same published design and should work, but are not tested.
- A TV that takes infrared commands. The remote sends infrared straight to the TV, so it needs a
  clear line of sight.

## Install

Download the APK from this repository's Releases page and install it over ADB:

```bash
adb connect <box-ip>:5555
adb install remotesmith.apk
```

On LineageOS for Raspberry Pi, ADB is switched on under Settings, System, Raspberry Pi settings.

If there is no release yet, build the app yourself (see Build) and install
`app/build/outputs/apk/debug/app-debug.apk` the same way.

## Use

**Set up TV buttons.** Pick your TV's brand. The app loads one set of codes onto the remote at a
time and asks whether the TV answered: first the volume buttons, then Mute, Power and Input. Say
no and it tries the next set. If your brand is not listed, or none of its codes work, the app goes
on with the sets most TVs answer to.

The codes are stored in the remote itself. They keep working when the app is closed, after a
reboot, and when the remote is paired to another box.

**Find my remote.** The remote beeps for up to 30 seconds; pressing any button on it stops the
sound. Since the remote is the thing that is lost, this can also be started without it, from a
keyboard or over ADB:

```bash
adb shell am start -a io.github.angrytechgremlin.remotesmith.action.FIND_REMOTE
```

Only remotes with a beeper can do this (the Google TV Streamer voice remote has one).

**Use my own codes.** For a TV the app has no code for, put a `profile.json` in the folder the app
shows under More, Use my own codes. It lists a Pronto hex code per button:

```json
{"volume_up": "0000 006D …", "volume_down": "…", "mute": "…", "power": "…", "input": "…"}
```

`tools/ircodes.py` can produce these from a NEC or Samsung address and command, or from a signal
learned with a Broadlink. `profiles/` has two complete examples.

## If something does not work

- **The remote does not answer.** Press any button to wake it and try again. Check its batteries.
- **The remote will not pair.** Open Settings, Remotes and accessories, Pair accessory, then hold
  Back and Home on the remote until its light pulses. A remote that was paired to this box before
  and then removed may refuse to pair until it is reset: hold Mute and the centre button until the
  light stays on. A reset also erases the remote's TV codes.
- **No code works for my TV.** Make sure nothing blocks the front of the remote. If the TV really
  is not covered, use your own codes as described above, and consider reporting the TV.
- **Power turns the TV off but the box stays on.** A button set up for the TV no longer reaches
  the box. If your TV and box both support HDMI-CEC, the box can follow the TV.
- **The device already has Google's remote setup** (Settings, Remotes and accessories, Set up
  remote buttons). Use that instead; the two would overwrite each other.

## What was tested

| | |
|---|---|
| Box | Raspberry Pi 5, LineageOS 23.2 (Android 16) |
| Remote | Google TV Streamer voice remote, two units |
| TV | Haier 43UG2500A |

Checked on that hardware: programming all five buttons, hold-to-repeat on the volume buttons, the
★ button as Input, every code form the remote supports, and find my remote (start, stop, time-out
and "found"). Everything else rests on the published design described below.

## How it works

Google publishes the firmware of its reference remote in the Android Open Source Project under
the Apache licence, and the remote makers' FCC manuals describe the same thing: the box writes
each button's infrared code to the remote over Bluetooth LE, and the remote stores it.
[docs/protocol.md](docs/protocol.md) describes the format and says which parts were verified on
real hardware.

The app is plain Java with no libraries: about 150 KB including the code database.

## Build

```bash
scripts/dev-setup.sh                                   # Linux, no sudo: adb, a JDK and the Android SDK
./gradlew assembleDebug testDebugUnitTest lintDebug   # the app, its unit tests and Android Lint
python3 -m unittest discover -s tools                 # the tools and the bundled database
```

On other systems, install adb, JDK 21 and the Android SDK your usual way.

`tools/build_db.py` rebuilds the code database (`app/src/main/assets/codes.txt`) from its sources.
It downloads them into `vendor/`, which is not committed.

## Contributing, with or without an AI agent

[agent.md](agent.md) is the working agreement for this repo. It covers setting up, switching on
ADB on the box, what to check on real hardware and how, and what must never be committed. It is
written so that you can point a coding agent at a clone and work the way this project was built:
the agent builds, installs, drives the app and reads the logs; you press the buttons on the remote
and say what the TV did.

## Where the codes come from

The bundled database is built from the [IRext offline database](https://github.com/irext/database)
(MIT licence) and from the files in [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) that
were contributed under CC0. See [NOTICE](NOTICE). It covers about 800 code sets across 237 brands.

## Licence and how this was made

Apache License 2.0; see [LICENSE](LICENSE).

The code was written with an AI assistant and tested on the hardware listed above by the project's
owner.
