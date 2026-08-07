# justrain

A calm rain-sound app. One tap of rain, a sleep timer that thins the rain out
over its last minute, a thunder toggle (visual lightning), and four settings.
That is the whole app.

Built as a **Tauri v2** application: the UI is a self-contained webview
frontend (`src/`), hosted by a small **Rust** core (`src-tauri/`), packaged as a
signed Android **APK**. The UI is a faithful port of the `Rain.dc.html` design
(Nocturne design system).

## Layout

```
src/                     frontend (Tauri frontendDist)
  index.html             screen markup + first-run download prompt
  style.css              Nocturne tokens + styles
  main.js                rain canvas, state machine, Web Audio engine
src-tauri/               Rust core
  src/lib.rs             tauri entry point + audio download/cache commands
  tauri.conf.json        app config (identifier page.osmosis.justrain)
  icons/                 app icon set (raindrop)
.github/workflows/android.yml   CI: build + sign + release
.github/scripts/patch_gradle.py signing + version injection for the generated Android project
.tools/tag_and_push.sh          cut a signed release
```

## Audio

The rain is a seamless ~11-minute crossfade loop. It is **not bundled** in the
APK — on first launch the app checks the app-data cache and, if empty, shows a
prompt asking the user to confirm downloading ~15 MB. The download runs in
**Rust** (`download_audio`, streaming with `download-progress` events) so it
isn't subject to webview CORS, saves to the app data dir, and thereafter plays
offline. It is decoded and played gaplessly via the Web Audio API
(`AudioBufferSourceNode.loop`); volume, soft-start fade-in, and the sleep-timer
fade-out use a `GainNode`.

- Source URL: `https://rain.osmosis.page/rain-loop-long.mp3` (hosted from the
  `static` repo's `public/rain/`).
- Rust commands: `audio_status` (cached? + remote size via HEAD),
  `download_audio` (stream + cache), `load_audio` (return cached bytes).

Attribution (required — CC-BY-3.0): the loop is derived from *"18 minutes of
raining and thundering"* by **Argande102** on Freesound
(<https://freesound.org/s/170438/>), trimmed, thunder-removed, and crossfade-looped.
See [`src/assets/ATTRIBUTION.md`](src/assets/ATTRIBUTION.md).

## CI / releases

`.github/workflows/android.yml` runs on every push and tag:

- **any branch push** → builds a **signed** APK and publishes a **prerelease**
  under the rolling tag `pre-<shortsha>`.
- **a version tag** `<shortsha>.<MAJOR>.<MINOR>` (e.g. `a1b2c3d.00.01`) →
  builds a **signed** APK and publishes a normal **release**.

`versionCode` is `git rev-list --count HEAD` (monotonic); `versionName` is the
tag (or short sha). The APK asset is `justrain-<version>.apk`.

### Cutting a release

```
.tools/tag_and_push.sh          # new short-sha, same MAJOR.MINOR
.tools/tag_and_push.sh minor    # MINOR + 1
.tools/tag_and_push.sh major    # MAJOR + 1, MINOR = 0
```

### Signing secrets (GitHub repo secrets)

| secret | meaning |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0` of the release keystore (`.jks`) |
| `ANDROID_SIGNING_STORE_PASSWORD` | keystore store password |
| `ANDROID_SIGNING_KEY_ALIAS` | key alias (`justrain`) |
| `ANDROID_SIGNING_KEY_PASSWORD` | key password |

The keystore itself is **never** committed. Keep a backup of the `.jks` and its
passwords somewhere safe — losing it means you can no longer ship updates that
install over an existing copy of the app.

## Local development (desktop)

```
cargo install tauri-cli --version "^2" --locked
cargo tauri dev          # runs the webview against src/
```

Android builds are produced by CI; to build locally you need the Android SDK +
NDK r27 and `cargo tauri android init && cargo tauri android build --apk`.

## Notes / limitations

- **Background audio**: the app requests a screen wake-lock and registers a
  media session, but true audio playback with the screen fully off/app
  backgrounded on Android needs a native foreground service — a future Rust/
  Kotlin Tauri plugin. The "keep playing when locked" switch reflects intent
  today; wake-lock covers the screen-dim case.
- **Thunder** is a visual lightning flash on the canvas; the rain loop is
  deliberately thunder-free audio.
