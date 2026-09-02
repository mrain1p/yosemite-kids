# Developing Pickwick (this fork)

## Toolchain

- JDK 17 (Temurin works). `JAVA_HOME` must point at it.
- Android SDK: platform 34, build-tools 34.0.0, platform-tools, emulator.
  On this Windows machine it lives at `%LOCALAPPDATA%\Android\Sdk`, installed
  with the command-line tools only (no Android Studio needed):

  ```powershell
  sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" "emulator" `
             "system-images;android-34;google_apis;x86_64"
  ```

- `local.properties` (git-ignored) points Gradle at it:

  ```
  sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
  ```

- Release signing: the fork's own keystore lives **outside the repo** at
  `~/.pickwick/pickwick-fork-release.keystore` (alias `pickwickfork`; the
  password sits beside it in `pickwick-fork-release.keystore.password.txt`).
  `local.properties` (git-ignored) carries `PICKWICK_KEYSTORE`,
  `PICKWICK_KEYSTORE_PASSWORD`, `PICKWICK_KEY_ALIAS`, `PICKWICK_KEY_PASSWORD`.
  **Back both files up off this machine**: Android refuses to upgrade an app
  across a signature change, so losing the key means every family device must
  uninstall (wiping its curation) to take the next build. `assembleRelease`
  refuses to run without the key by design.

## Outbound connections (what the app talks to)

| Where | When | Controlled by |
| --- | --- | --- |
| YouTube (`youtube.com`, `googlevideo.com`) | browsing and playback | the whitelist |
| SponsorBlock (`sponsor.ajay.app`) | while playing, hashed video-id prefix only | parent setting *Skip sponsors* |
| The AI endpoint the parent configured | screening, if enabled | parent settings |
| `PICKWICK_UPDATE_URL` (`version.json`) | once per app process, for the update dot | build property — **blank in this fork** = no check |
| `PICKWICK_DIRECTORY_URL` (upstream's community directory, read-only) | only when a parent opens *Suggested channels* | build property |
| `PICKWICK_SUGGEST_URL` (upstream's Cloudflare worker) | only when a parent presses *Submit list to directory* | build property |

Set the properties in `local.properties` or the environment before
`assembleRelease`. Point the update URL at your own fork's raw `version.json`
once the fork exists on GitHub; until then the app never phones anywhere for
updates.

## The check (run before every commit)

```powershell
.\scripts\check.ps1          # compile, unit tests, worker tests
.\scripts\check.ps1 -Quick   # compile only
```

or on a POSIX shell `scripts/check.sh`. It runs:

1. `gradlew assembleDebug` — the app compiles.
2. `gradlew :app:testDebugUnitTest` minus `ExtractorSmokeTest` (live YouTube).
3. `node --test worker/test/*.test.mjs` — the Cloudflare Worker's pure helpers.

CI (`.github/workflows/build.yml`) runs the same on every PR.

## Seeing it run: the emulator loop

Sideloading onto a real TV for every change is slow. The emulator is the
day-to-day loop; the real device is for final checks (playback performance,
remote ergonomics).

```powershell
.\scripts\emu.ps1 boot        # start the AVD "pickwick_phone" (Pixel 6, API 34)
.\scripts\emu.ps1 install     # build debug + adb install -r
.\scripts\emu.ps1 seed        # push a sample config.json with a few channels
.\scripts\emu.ps1 launch      # start MainActivity
.\scripts\emu.ps1 shot home   # screenshot → build/shots/home.png
.\scripts\emu.ps1 tv          # boot the Android TV AVD instead (if created)
```

The emulator window is a normal window on the desktop: click, type, rotate.
Claude drives it the same way through `adb shell input` and reads screenshots.

Creating the AVDs once:

```powershell
avdmanager create avd -n pickwick_phone -k "system-images;android-34;google_apis;x86_64" -d pixel_6
sdkmanager "system-images;android-34;android-tv;x86_64"
avdmanager create avd -n pickwick_tv -k "system-images;android-34;android-tv;x86_64" -d tv_1080p
```

Hardware acceleration: `emulator -accel-check` must say WHPX (or HAXM/AEHD)
is usable. On Windows 11 Home enable *Windows Hypervisor Platform* under
"Turn Windows features on or off" if it isn't.

### Seeding a config without the settings UI

A debug build allows `run-as`, so a config can be dropped straight into the
app's files directory:

```powershell
adb shell run-as io.pickwick.app mkdir -p files
adb push scripts\seed-config.json /data/local/tmp/config.json
adb shell run-as io.pickwick.app cp /data/local/tmp/config.json files/config.json
adb shell am force-stop io.pickwick.app
```

`scripts/seed-config.json` is a small family: three channels (TED-Ed, SciShow Kids, Sesame Street), one
kid with a 15-minute session rule, so the time chip and end cards show up.

### Real devices over Wi-Fi

Wireless debugging (Developer options on the phone/TV) makes `adb install -r`
the whole deploy — no Downloader app, no APK copying. The TV shows up in
`adb devices -l` on its own once enabled; see CLAUDE.md for the quirks.

## Icons, banner, store assets

The launcher icon is adaptive (`drawable/ic_launcher_foreground.xml` on
`@color/ic_launcher_background`). TV launchers use the separate 16:9
banner `drawable-*/tv_banner.png` (`android:banner` in the manifest) — a
square icon there is stretched into the row. Regenerate the banner and the
store assets (`docs/store/`: 512×512 icon, 1280×720 feature graphic) after a
logo or colour change:

```bash
java -Djava.awt.headless=true scripts/Banner.java app/src/main/res docs/store
```

## Where things are

`docs/ARCHITECTURE.md` is the map; `docs/LAN-API.md` is the phone↔device
protocol; `docs/FORK-NOTES.md` is what this fork changed and why, plus the
open backlog.

## Claude Code skills

`.claude/skills/` carries repeatable workflows: `pickwick-check` (build+test),
`pickwick-emulator` (boot/install/drive/screenshot), `pickwick-lan-api`
(protocol reference, safe probing), `pickwick-release` (version bump, APK,
version.json). Invoke with `/pickwick-check` etc.
