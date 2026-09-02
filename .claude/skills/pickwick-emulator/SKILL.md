---
name: pickwick-emulator
description: Boot the Android emulator, install the debug Pickwick APK, seed a sample family config, launch, drive with adb input, and take screenshots. Use whenever a change needs to be seen or exercised without sideloading to a real device.
---

# Pickwick on the emulator

`scripts/emu.ps1` wraps everything; each verb is idempotent.

```powershell
.\scripts\emu.ps1 boot          # AVD pickwick_phone (Pixel 6, API 34, WHPX)
.\scripts\emu.ps1 install       # gradlew assembleDebug + adb install -r
.\scripts\emu.ps1 seed          # scripts/seed-config.json -> app files (debug run-as)
.\scripts\emu.ps1 launch        # am start MainActivity
.\scripts\emu.ps1 shot <name>   # screencap -> build/shots/<name>.png (Read it to look)
.\scripts\emu.ps1 tap X Y | key KEYCODE | text "..." | back | home | rotate
.\scripts\emu.ps1 logcat        # last 200 Pickwick log lines
.\scripts\emu.ps1 stop
```

## Always target the emulator by serial

Other Android devices may be plugged into this machine (a Quest headset has
been seen). Every adb call must carry `-s emulator-5554` — the script does;
raw `adb` calls must too. Never install to or touch a physical device
without the user asking.

## Typical loop

1. `install` -> `seed` -> `launch` -> wait ~5 s -> `shot home`.
2. Read the PNG (the Read tool renders images). Home should show the seeded
   channels within a few seconds once the emulator has internet.
3. Drive: `tap` on a channel tile, `shot channel`; `tap` a poster, `shot player`;
   `key KEYCODE_BACK`. Coordinates are in the 1080x2400 (portrait) frame;
   the player forces landscape, so screenshots there are 2400x1080.
4. Time-based screens: the seed gives the kid a 2-minute session so the "N min
   left" chip, the 1-minute warning and the break card all appear quickly.

## TV layout

Create once: `sdkmanager "system-images;android-34;android-tv;x86"` then
`avdmanager create avd -n pickwick_tv -k "system-images;android-34;android-tv;x86" -d tv_1080p`.
`.\scripts\emu.ps1 tv` boots it on **emulator-5556** so the phone can stay
up; set `$env:PICKWICK_SERIAL="emulator-5556"` and use the same verbs
(`install`, `seed`, `launch`, `shot`, `dpad left|right|up|down|ok|back [n]`,
`hold-ok`). Screenshots are 1920x1080.

TV gotchas, all seen on this machine:

- **The Android TV launcher steals the foreground.** Right after boot (and
  sometimes minutes later) it pops a "Buy and rent movies" promo over
  whatever is running; key presses then drive the launcher's Shop page, not
  Pickwick. Before any key sequence check
  `adb -s emulator-5556 shell dumpsys window | grep mCurrentFocus` contains
  `io.pickwick.app`; if not, `KEYCODE_HOME`, dismiss the promo, `launch` again.
- **Initial focus** is the first tile of the topmost video row (Keep
  watching if present, else New for you). `dpad down` once reaches the
  channel chips, twice the Watched lately row.
- **A plain key can register as a hold** when the emulator is starved (Gradle
  building at the same time): `input keyevent` sends down/up and a slow
  guest can exceed the long-press threshold, so OK opens the action menu
  instead of playing. Don't build while driving the TV.
- The player has no focusables: `dpad down` opens the ▼ toolbar (Audio,
  Subtitles, ❤️, channel avatar), left/right moves, OK selects, `back` closes.

## Where the seed comes from

`scripts/seed-config.json` — edit it to add channels (`entries[].url`) or
change rules (`profiles[].limits`). It is a real `config.json`, the same
shape `ConfigStore.toJson` writes. `seed --real` pushes
`scripts/seed-config.real.json` (the family's own list, built by
`scripts/seed-from-whitelist.sh`, git-ignored). To try a parent setting
without the PIN flow, add it to the seed at top level, e.g.
`"channelLayout": "playlists"` or `"popular"`, `"channelOrder": "alpha"` or
`"random"`, `"autoplay": false`.

## Reading the result

Playback and fetch events are `Pickwick:` log lines (`chunked clen` = the
stream started, `open <id> failed`, `playlist shelves for <id>: n/m filled`,
`open channel by name`). An `ActivityManager: ANR in io.pickwick.app` with
`DiskLruCache.initialize` contention is the emulator's slow disk, not the app;
wait it out and retap.
