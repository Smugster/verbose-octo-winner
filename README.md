# SCTV+ thin shell (prototype)

A small Android app that plays the SCTV+ player and **updates the player by itself**, signed and checked, without a new APK
(requirement E7-14). GitHub does the signing, publishing and APK building; you click buttons and install the APK once.

## Quick start (about 10 minutes)
1. **Create a repository on GitHub:** New repository, any name (for example `sctv-player`), **Public**, nothing else ticked, Create.
2. **Add two secrets** (Settings > Secrets and variables > Actions > New repository secret):
   - `SIGNING_KEY`: paste everything in `thin-shell-SIGNING_KEY.txt`, including the BEGIN and END lines. Then delete that file.
   - `SCREEN_DEVICE_ID`: the screen's device ID from the management website (for HSTV-Deli, its record ID).
   - Optional, on the **Variables** tab: `SCREEN_LINES` (for example `Deli Line`) and `SCREEN_MEAL` (`Lunch`, `Breakfast` or `auto`).
3. **Upload the files:** on the new repository's page, click *uploading an existing file*, drag in **everything inside** the unzipped
   `thin-shell` folder, and Commit. On a Mac, press Cmd+Shift+. in Finder first: the `.github` folder is hidden and must be included.
4. **Wait about 5 minutes.** The Actions tab runs *Release player* and *Build app*. When *Build app* has a green tick, open it, download
   **sctv-shell-apk** under Artifacts, unzip it, and install: `adb install -r app-debug.apk`.
5. **Try updates:** Actions > *Release player* > Run workflow, choose what to release, Run. The screen picks it up within a few minutes
   (it checks every minute; GitHub's file cache can add up to 5).

| Release | What the screen does |
| --- | --- |
| normal | Switches to the new build; the corner tag shows its number; status "build N is running" |
| broken | Switches, then a minute later rolls back: "build N failed (it didn't report ready within 60 s); back to build M" |
| rollout-0 | Ignores it: "build N isn't rolled out to this screen yet (0%)" |
| tampered | Refuses it: "refused an update: its signature is not Cybersoft's"; the current build keeps playing |

After *tampered*, release *normal* to put a signed release back. Changing `player/` in the repository also releases a new build.
If a workflow turns red, open it: the first error line says what's missing (usually a secret).

**About the key:** this prototype's key was made for you here, so treat it as a test key. For real screens, Cybersoft makes its own
key inside the company (`python3 tools/keygen.py`), keeps it secret, and builds the app with it.

---

| Folder | What it is |
| --- | --- |
| `android/` | The shell app (Java, no extra libraries). `assets/shell-config.json` holds the update address and the screen's settings. |
| `tools/keygen.py` | Makes the signing key pair. The private key stays in `keys/` (never committed). |
| `tools/release.py` | Signs a player release into `site/`, the folder you push to GitHub. |
| `site/` | What the update host serves: `manifest.json`, `manifest.sig`, `versions/<build>/`. |
| `test/` | Tests of the update logic: `java test/RunTests.java` (22 checks, a local web server, real signed releases). |

## How an update works
1. The shell plays the build stored on the screen, or the player inside the APK (`assets/floor`) when there is none, so it works offline from first boot.
2. Every `checkMinutes` it fetches `manifest.json` and `manifest.sig`. It ignores the release unless the **signature** matches the key built
   into the app, the **build number** is newer than the one playing (so an old signed release can't be replayed), the build was never
   rolled back here, and the screen falls inside the **rollout** percentage (the same screens go first every time).
3. It downloads every file and checks its **size and SHA-256**; file paths can't leave the build's folder.
4. It switches at a **safe moment** (the player's `window.sctvSafeToSwitch()`: outside serving times) and puts the build on **probation**:
   the player must call `SctvShell.ready(build)` within `probationSeconds`, and the app may restart at most twice meanwhile. Otherwise
   the shell **rolls back** to the previous build (or the built-in player) and never retries that build.

`release.py` adds the hook that does the player's part (ready, safe to switch, a "Player build N" tag), and replaces the PoC's
`poc-config.js` so **screen settings come from the APK**, never from the public host. It warns if a release contains ID-like values.

## Doing it by hand (without GitHub Actions)
1. **Make the signing key:** `python3 tools/keygen.py`. Back up `keys/private.pem` somewhere safe and keep it secret: whoever has it can
   ship code to every screen. (A GitHub account takeover alone can't: screens refuse releases without this key's signature.)
2. **Make the first release, and put it inside the app too:**
   `python3 tools/release.py --source ../poc-showcase/web --build 1 --version "Showcase 1" --floor`
3. **Publish `site/` on GitHub Pages:** create a repository (public: Pages on a private repository needs a paid plan, and screens can't sign in),
   push the *contents* of `site/` to its root, then Settings → Pages → Deploy from a branch → `main`, `/ (root)`.
   Check that `https://YOUR-NAME.github.io/YOUR-REPO/manifest.json` opens in a browser.
4. **Set up the app:** in `android/app/src/main/assets/shell-config.json`, set `updateUrl` to that address (ending in `/`) and the screen's
   `player.deviceId`, `lines` and `meal`. For testing, set `"switchPolicy": "now"` and `"checkMinutes": 1`.
5. **Build and install:** `cd android && ./gradlew assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   The status line at the bottom right shows the shell, the build playing and the last update check.

## What to try on a device
| Test | Do this | Expect |
| --- | --- | --- |
| Update | `release.py --build 2 --version "Showcase 2"`, push `site/` | Within a check or two: status "switched to build 2", then "build 2 is running"; the tag says Player build 2 |
| Rollback | `release.py --build 3 --broken`, push | It switches to 3; a minute later: "build 3 failed (it didn't report ready within 60 s); back to build 2" |
| Tampering | Edit `manifest.json` on GitHub (change `rollout`) without re-signing | "refused an update: its signature is not Cybersoft's"; build 2 keeps playing |
| Staged rollout | `release.py --build 4 --rollout 0`, push | "build 4 isn't rolled out to this screen yet (0%)" |
| Offline | Turn Wi-Fi off and restart the app | The stored build plays; the status says it couldn't check |

After a tampering test, publish the next build number with `release.py` to put a signed manifest back.

## Notes
- **Starting on boot (Android 10 and later):** Android only lets an app open itself after boot if it may *display over other apps*. Grant it
  in Settings → Apps → SCTV+ Shell, or through the district's management software (for Clear Touch panels, Command). Today's apps
  request the same permission for the same reason.
- **Logs:** `adb logcat -s SctvShell`. The shell's state is in the app's private files (`files/shell/state.json`).
- **Not in this prototype:** the serial number, remote screenshots, heartbeats, the Chromebox version, a release-signed APK, and the
  schedule-based safe moment (it uses placeholder serving times).
