# Remotesmith: ways of working

Remotesmith is an Android TV app that sets up the TV buttons (infrared) of Google
reference-design voice remotes and makes them beep when lost, for boxes that lack Google's own
remote setup. The repo exists to hold one rule: **nothing is sent to a remote unless
`docs/protocol.md` describes it, and nothing counts as working until someone has seen it work
on real hardware.**

This document is the contract for anyone working here, human or AI agent. `CLAUDE.md` imports
it and `AGENTS.md` points to it, so an agent started in a clone reads it first. It is written so
that a person with a TV box, a remote and a TV can hand the keyboard to an agent and get real
work done the same day: the agent builds, installs, drives the app and reads the logs; the
person presses buttons on the remote and says what the TV did.

## 1. Core principles

- **Prove it on hardware.** A protocol fact is a guess until the TV has reacted or the remote
  has beeped in front of a person. `docs/protocol.md` says, rule by rule, what has been seen.
  Keep that honest when you add to it.
- **Public sources only.** Everything here is built from the reference remote firmware Google
  publishes in AOSP, the remote makers' FCC manuals, and our own measurements. No code, data,
  server or branding of Google's goes in. If something can only be done with a secret that
  Google holds, stop; do not work around it.
- **The remote in the room is someone's working remote.** Programming replaces all its TV
  buttons at once. Ask before the first write, and put back what was there (§5).
- **Simplicity and minimalism.** Plain Java, no libraries, one activity. Prefer deleting to
  adding. A new setting needs its reason written next to it.
- **Code explains how; docs explain why.** A behaviour change gets a line in the README saying
  what it fixes and what it trades off.

## 2. Repository topology

| Path | What it is |
|---|---|
| `app/` | The app. `MainActivity` shows every page; `Setup` is one wizard run; `CodeDb` reads the bundled codes; `IrCodec` is the remote's code format; `RemoteLink` is the Bluetooth session; `DebugCommands` takes commands over ADB in debug builds. |
| `app/src/main/assets/codes.txt` | The bundled TV code database. Generated; do not edit by hand. |
| `tools/` | `ircodes.py` (protocols, timings, Pronto, the remote's format), `build_db.py` (builds the database), `make_art.py` (launcher banner), and their tests. |
| `docs/protocol.md` | How the remote is programmed and what was verified. |
| `docs/adr/` | Decisions and the trade-offs behind them. |
| `profiles/` | Example hand-made setups for "Use my own codes". |
| `scripts/` | `dev-setup.sh` (toolchain without sudo), `pii-scan.sh` (see §3). |

Never tracked: `.env`, `pi.md`, `local.properties`, `vendor/` (downloaded database sources),
`captures/` (device logs, screenshots, test profiles), build output.

## 3. Secrets and personal data

This repo is public. Nothing personal goes into a tracked file, a commit message, an issue or a
pull request.

- **Never commit:** passwords, tokens, real names, usernames, e-mail addresses, home directory
  paths, LAN addresses, MAC addresses, serial numbers or device names. Use placeholders
  (`<box-ip>`) or the documentation ranges (`192.0.2.x`, `198.51.100.x`, `203.0.113.x`).
- **Real values live in ignored files.** The box's address goes in `.env` (copy
  `.env.example`). Everything else about your setup goes in `pi.md` (short for personal
  information): which remote is which, what TV you have, what is half-done. Both are gitignored.
  An agent reads `pi.md` at the start of a session and keeps it current.
- **Device output is personal data.** `dumpsys bluetooth_manager` and `logcat` list the names
  and addresses of every Bluetooth device in range, yours and your neighbours'. Read them;
  never paste them raw. Mask addresses (`XX:XX:XX:XX:…`) and save anything you keep under
  `captures/`.
- **Before every push:** `scripts/pii-scan.sh`. It is also the pre-push hook and a CI job. It
  finds credentials, private addresses, MAC addresses and coordinates, in binary files too. It
  does not know your name: also look for usernames in paths, e-mail addresses and device names
  yourself. Never weaken a pattern to get a push through; fix the file, or add a narrow path to
  `scripts/pii-scan.exclude` with the reason.
- **Look at `git status` before `git add -A`.** An editor that has `.env` or `pi.md` open keeps
  a swap copy beside it. `.gitignore` covers the usual names; a new kind of copy is yours to
  notice.
- The Git author name and e-mail of every commit are public and permanent. Check
  `git config user.email` before the first commit. An edit made on github.com is committed with
  your account's primary address unless "Keep my email addresses private" is on (GitHub,
  Settings, Emails), so switch that on before your first edit there.

## 4. Getting set up

### 4.1 The development machine

```bash
scripts/dev-setup.sh          # Linux x86_64, no sudo: adb, JDK 21, Android SDK under ~/.local/opt
export JAVA_HOME=~/.local/opt/jdk-21 ANDROID_HOME=~/.local/opt/android-sdk
export PATH=~/.local/opt/platform-tools:$PATH
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 -m unittest discover -s tools
```

On another system, install adb, JDK 21 (what CI uses) and the Android SDK your usual way and set
the two variables. The tools need only Python 3; `make_art.py` also needs Pillow.

### 4.2 What an agent must ask its human for

Do not guess any of these. Ask, then write the answers into `.env` and `pi.md`.

1. **The box's ADB address**, and that debugging is switched on (§4.3). It goes in `.env` as
   `BOX_ADB=<box-ip>:5555`.
2. **Which remote is paired** and that it moves around the screen right now.
3. **Whether that remote already has TV buttons they rely on**, and how they were set up. The
   remote cannot be read back (§5).
4. **The TV's brand and model**, and whether they still have the TV's own remote.
5. **Permission for the things that affect them:** installing the app on the box, the first
   write to the remote, anything that beeps, and every commit or push.

### 4.3 Turning on ADB over the network

TV boxes rarely have a USB port to spare, so the agent connects over the network.

- **LineageOS for Raspberry Pi (KonstaKANG's builds):** Settings, System, Raspberry Pi
  settings, ADB.
- **Other Android TV builds** (names vary): Settings, System, About, then press the build entry
  ("Build number" or "Android TV OS build") seven times to unlock Developer options. Then
  Settings, System, Developer options: turn on USB debugging and, where there is one, ADB over
  network.

Then, from the development machine:

```bash
adb connect <box-ip>:5555
adb devices                   # the box should be listed as "device"
```

If the TV asks whether to allow debugging from this computer, the human approves it there.

### 4.4 Rooted debugging: only when there is a reason

**Nothing in this repo needs root.** The app is an ordinary app, a debug build shows its own
data through `adb shell run-as`, and everything in §5 works as the normal shell user.

Root is for digging below the app, such as reading the Bluetooth stack's own files. If you get
there:

1. Tell your human what you want it for, and that the box may need a reboot afterwards.
2. They switch it on: Settings, System, Developer options, Rooted debugging (LineageOS; unlock
   Developer options as in §4.3).
3. Only then run `adb root`, and reconnect with `adb connect` afterwards.

Never run `adb root` just to see whether it works. On LineageOS 23.2 for the Raspberry Pi 5,
`adb root` with Rooted debugging off was refused and took ADB off the network: port 5555
stopped answering, switching ADB off and on in the settings did not bring it back, and only a
reboot did. `adb root` with Rooted debugging on has not been tried on that build since.

### 4.5 Pairing and resetting a remote

- **Pair:** on the box, Settings, Remotes and accessories, Pair accessory. On the remote, hold
  Back and Home until its light pulses; it stays in pairing mode for about 85 seconds. If the
  list shows more than one device, someone has to pick the remote, with a keyboard or another
  remote.
- **Reset:** hold Mute and the centre button until the light stays on. This erases the pairing
  and the remote's TV codes. A remote that was paired to this box before, and then removed on
  the box, may refuse to pair until it is reset.
- **Leave the box alone while a remote is being paired.** Launching or installing anything
  pushes the pairing screen away and leaves the remote half-connected. Check what is in front
  first (§5).

### 4.6 A first session, in order

1. `scripts/dev-setup.sh`, then build and run the tests (§4.1). No device is needed for this.
2. Ask the questions in §4.2. Write the answers into `.env` and `pi.md`.
3. `adb connect`, check what is in front and that the remote is connected (§5).
4. Install the debug build and send `dump` (§5). It only reads, and its log shows whether the
   remote has the two services in `docs/protocol.md`.
5. From here on, every write to the remote follows "Leave the remote as you found it" (§5).

## 5. Working with real hardware

### Who does what

| The agent, on its own | Needs the human |
|---|---|
| Build, test, install, launch | Press buttons on the remote |
| Send debug commands and read the app's log | Say whether the TV reacted or the remote beeped |
| Drive the app's pages and take screenshots | Pair or reset a remote, change a setting on the box |
| Read the box's state | Decide whether the remote may be overwritten |

### What an agent can check without anyone

```bash
set -a; . ./.env; set +a                                  # BOX_ADB
APP=io.github.angrytechgremlin.remotesmith
adb connect "$BOX_ADB"

# What is in front? If it is AddAccessoryActivity, a remote is being paired: wait.
adb -s "$BOX_ADB" shell dumpsys activity activities | grep topResumedActivity
# Is the remote connected? One line per paired input device; "HOGP connection state=2" means yes.
adb -s "$BOX_ADB" shell dumpsys bluetooth_manager | grep "HOGP connection state"

adb -s "$BOX_ADB" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$BOX_ADB" logcat -s Remotesmith:I                 # the app's log: every write and reply
adb -s "$BOX_ADB" shell input keyevent KEYCODE_DPAD_DOWN  # drive the pages: DPAD_*, DPAD_CENTER, BACK
adb -s "$BOX_ADB" exec-out screencap -p > captures/page.png      # then look at the picture
adb -s "$BOX_ADB" shell run-as $APP cat shared_prefs/setup.xml   # the saved setup (debug builds)
```

Debug builds also take commands, answered in the log (`DebugCommands.java`):

```bash
adb -s "$BOX_ADB" shell am start -n $APP/.MainActivity --es cmd dump
#   dump                       list the remote's Bluetooth services (reads only)
#   profile --es profile JSON  put a hand-made setup on the remote (format: Profiles.java)
#   clear                      remove the remote's TV codes
#   listen --ei seconds N      log every press of a programmed button for N seconds
#   beep --ei seconds N        beep for N seconds (0 stops); --ei wait M waits M minutes for a sleeping remote
#   suppress | unsuppress      send Volume and Mute over Bluetooth instead of infrared, and back
```

The remote reports its own button presses (`listen`) and answers every beep request, so a
surprising amount can be confirmed from the log alone. Whether infrared left the remote and
whether the TV understood it, only a person can say.

### Asking the human

- **One thing at a time.** "Press Volume Up once. Did the TV's volume change?" works. A list of
  four timed steps does not.
- **Offer the answers:** yes, no, did not look.
- **No time windows.** Wait for the event in the log instead of asking for something "within
  the next two minutes".
- **Warn before a beep,** and before the screen is about to go dark in a power test.
- **The box is someone's TV.** If another app is in front, ask before you put yours on top of
  it.
- "Not sure" means not verified. Write it down as not verified.

### Leave the remote as you found it

A programming session wipes every TV button on the remote before writing the new ones, and
nothing can be read back. So:

- Before your first write, find out what is on the remote (§4.2). If it was set up by this
  app, the app has a copy: the home page offers "Send the saved setup to a remote". If it was
  set up some other way, say plainly that it will be lost.
- When a test changes the codes, finish by putting the working set back, and check that the
  log ends with `upload: done`.
- Stopping the wizard does this by itself: it keeps what was confirmed, or restores the saved
  setup.

### Things that go wrong

| What you see | Why | What to do |
|---|---|---|
| The log says `failed, UNREACHABLE` | The remote is asleep or out of range | Ask for any button press, then retry |
| The remote will not pair again | It was paired here before and removed | Reset it (§4.5), then pair |
| A timer or a waiting job never fires | Android froze the app because it left the screen | Keep the app in front; it holds the screen on |
| A running test dies | `adb install` restarts the app | Check nothing is running before installing |
| Port 5555 refuses connections | `adb root` was run (§4.4) | Reboot the box |
| Buttons do nothing after an upload | The session listed only some buttons | Always send the whole set (`docs/protocol.md`) |

Never write to the remote's firmware update service (`00010203-0405-0607-0809-0a0b0c0d1912`),
and never send a code that `IrCodec.problem` rejects: the reference firmware trusts parts of a
code without checking them (`docs/protocol.md`, "Code format").

## 6. Branching, commits and releases

- `main` is the development branch. A release is a tag `vX.Y.Z` with a signed APK attached to
  it; nothing is released by merging.
- **Commit and push only when your human asks.** Conventional Commits (`feat:`, `fix:`,
  `docs:`, `chore:`, `test:`) with subjects that say why.
- The release signing key never enters the repo, a log or CI output.
- The app id, `io.github.angrytechgremlin.remotesmith`, is permanent: Android treats a different
  id as a different app. If you publish builds of a fork, give them your own id and signing key.

## 7. Testing

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug   # the app: unit tests and Android Lint
python3 -m unittest discover -s tools                 # the tools and the shipped database
scripts/pii-scan.sh
```

- **Unit tests** run on the development machine and need no device: the encoder against
  reference vectors, the database reader against the shipped file, and whole wizard runs.
  `tools/testdata/codec_vectors.txt` keeps the Python and Java encoders identical; regenerate
  it with `python3 tools/ircodes.py vectors` when the format changes.
- **CI** runs the three commands above on every push. It has no device and no secrets.
- **Hardware checks** are done by hand as in §5 and never run in CI.

A change is done when the commands above pass, the affected pages were looked at on a device
(or the change says they were not), `docs/protocol.md` and the README still tell the truth, and
the remote was left working.

## 8. Code style

- **App:** Java, no runtime libraries, plain views that work with a D-pad. Every text a user
  sees lives in `res/values/strings.xml`, in plain words. Escape quotation marks there
  (`\"`), or Android drops them.
- **Tools:** Python 3, standard library only for `ircodes.py` and `build_db.py`.
- **Shell:** `set -euo pipefail`, shellcheck-clean, no `sudo`.
- Match the comment density and naming of the file you are in.

## 9. The code database

- `app/src/main/assets/codes.txt` is built by `tools/build_db.py` from the IRext offline
  database (MIT) and the Flipper-IRDB files that were contributed under CC0. See `NOTICE` and
  `docs/adr/`.
- **Only openly licensed sources may be added:** your own captures, CC0, MIT and the like.
  Several well-known collections are not usable (irdb's conditional licence, Flipper-IRDB
  files older than its CC0 licence, LIRC, Global Caché). When in doubt, leave it out.
- To rebuild: `python3 tools/build_db.py --report`. It downloads its sources into `vendor/`
  and prints how the best-known brands come out. `tools/test_db.py` must still pass.
- A TV that is not covered does not need a new database: "Use my own codes" in the app takes a
  `profile.json`, and `tools/ircodes.py` can make one from a NEC or Samsung address and
  command or from a signal learned with a Broadlink.

## 10. Decision log

Every trade-off against a principle in §1 gets a short record in `docs/adr/NNNN-title.md`, one
screen at most. `docs/adr/README.md` is the index. Supersede a decision; do not rewrite it.

## 11. Lessons learned

- A programming session replaces the whole table. Sending only the button under test erases the
  others.
- A remote keeps its Bluetooth link while idle (40 minutes and counting on the test setup), so
  a beep usually arrives at once. Do not assume that of every remote.
- The Google TV Streamer remote has a ★ button where older remotes have Input. The input code
  is written under both ids; a remote ignores the one it lacks.
- The source data marks a button the original remote does not have with a placeholder code.
  `build_db.py` drops those; a naive import would ship hundreds of dead Mute and Input codes.
- Within a brand, the code set most of its remotes share is a better first guess than the
  source's own ranking; widely used sets get a head start in every brand.
- The Android Gradle plugin adds the Kotlin standard library to a Java-only app unless
  `android.builtInKotlin=false` is set: 2.4 MB for nothing.
- A count such as "code 1 of 474" discourages more than it informs. The wizard counts only a
  brand's own volume codes.

## 12. Local overrides

These are the defaults for the whole repo. A `README.md` or `agent.md` inside a subdirectory
you are working in overrides them there.
