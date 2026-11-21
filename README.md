# VidGrab

A small, self-contained Android app for downloading videos (or just their audio) from the web. Paste or share a link, pick a resolution, tap **Download**.

VidGrab bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg through the [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) library, so it works with any site yt-dlp supports. No server is involved: everything runs on the phone.

## Features

- **Automatic link checking.** Paste one or more links (one per line), tap *Paste*, or share a link to VidGrab from another app. Every new link is checked in the background and its available resolutions are listed.
- **Per-video resolution choice.** You can pick a specific height (with an approximate size), *Best available*, or *Audio only (MP3)*.
- **Batch downloads.** Up to two downloads run at the same time, and *Select all* ticks every item.
- **Pause, resume and stop.** *Pause* keeps the partial file and *Start* continues from it. *Stop* discards the partial file.
- **Survives bad connections.** When the connection drops or switches (for example mobile data to Wi-Fi), downloads stop at once and resume from the partial file on the new connection. If a resumed download stays far slower than it was before the interruption, it reconnects (at most twice). Progress, speed and ETA stay visible through interruptions. Readable messages are shown, such as "This video is private" or "Phone storage is full".
- **Wi-Fi only by default.** Downloads wait for Wi-Fi (a VPN running over Wi-Fi counts). Untick *Download on Wi-Fi only* to use mobile data as well. Checking links still works on mobile data, because it uses very little.
- **Background downloads.** A foreground service with a notification and a wake lock keeps downloads running when the app is in the background. Pressing *Back* during a download sends the app to the background instead of closing it.
- **Optimize.** FFmpeg remuxes the file without re-encoding. This fixes timestamps, drops corrupt packets and moves the MP4 index to the front ("faststart") so the video starts playing faster and seeks better. It can run automatically after each download, later on downloaded items, or on any video already on the phone (saved as a new "(optimized)" copy).
- **Choose where files go.** The default is `Download/VidGrab`. You can pick any folder through the system folder picker.
- **Engine updates.** The app checks for a new yt-dlp once a day at startup (about 3 MB), because sites change often and an outdated engine stops working with them. *More → Update engine* updates it on demand.
- **Error details.** Tap a red error message, or the status bubble after a failed link check, to see the full error text and the engine version, with a *Copy* button.
- **List is remembered.** The list of items, their chosen resolution and their saved files are kept between launches.

## Requirements

| | |
|---|---|
| Android | 10 (API 29) or newer |
| CPU | ARM: `arm64-v8a` or `armeabi-v7a`. For an x86_64 emulator, build with `-Pvidgrab.abis=x86_64`. |
| Storage | Free space of roughly **2× the video size**. Downloads are assembled in app storage and then copied to your folder, and optimizing needs one more temporary copy. |

## Installing

Every push to GitHub builds a debug APK through GitHub Actions.

1. Open the repository's **Actions** tab and select the latest successful **Build APK** run.
2. Download the **VidGrab-apk** artifact and unzip it.
3. Copy the `.apk` to your phone and open it. You may need to allow "Install unknown apps" for your file manager or browser.

## Building from source

The repository has no Gradle wrapper, so you need Gradle installed:

- JDK 17
- Gradle 8.7 (required by Android Gradle Plugin 8.5.2)
- Android SDK with platform 34. Point to it with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=...`.

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Android Studio can also open the project directly.

## Automated tests

Every push runs two GitHub Actions jobs:

- **build** compiles the APK. On failure, Kotlin errors show up as annotations on the run's page.
- **e2e** starts an Android 13 emulator, installs the app and uses it for real, with the bundled yt-dlp, Python and FFmpeg and a real network (`app/src/androidTest`, run by `ci/e2e.sh`):
  - `EndToEndTest` (must pass): pastes a link to [testdata/vidgrab-test.mp4](testdata/vidgrab-test.mp4), downloads it, checks the saved file was optimized (its MP4 index moved to the front), runs *Optimize ticked files*, then *Delete ticked videos* and checks the file is gone. It also downloads *Audio only (MP3)* and checks the result is an MP3, and checks *Remove ticked unfinished*.
  - `YouTubeTest` (warning only): downloads a short YouTube video. YouTube often blocks CI's data-center IPs, so a failure there is reported as a warning rather than failing the run.

Test results, the HTML report and logcat are in the run's **e2e-results** artifact. The tests drive the app through a small set of hooks in `MainActivity` (see "UI TEST CONTRACT" there); keep them working when changing the UI.

To run the tests locally on a connected device or emulator: `gradle connectedDebugAndroidTest` (add `-Pvidgrab.abis=x86_64` for an x86_64 emulator).

## How to use

1. Paste links into the box, tap **Paste**, or use **Share → VidGrab** from a browser or app.
2. Each link gets a card with its thumbnail (with the video length), title, site and uploader. Pick a quality in the pill under the title.
3. Tick the videos you want (tap the circle on the thumbnail, or anywhere on the card) and tap **Download (N)** in the bottom bar. Each card also has its own **Start / Pause** button and a round **Stop** button. The status line is blue while working, amber while waiting, red on errors and green when done.
4. Finished files go to the folder shown in **Settings → Save to** (tap the row to change it). Finished cards switch to **Open** and a round **Share** button.

The app follows the system's light or dark theme.

Everything else is in the **More** sheet in the bottom bar:

| Action | What it does |
|---|---|
| Remove completed | Clears finished items from the list. Files are kept. |
| Remove ticked unfinished | Removes the *ticked* items that haven't finished, stopping them if they are running, and discards their partial files. |
| Delete ticked videos | Deletes the *ticked* videos, finished or not. Saved files are deleted from the phone, unfinished downloads are stopped and discarded, and the cards are removed. |
| Optimize ticked files | Remuxes the saved files of the ticked items in place. |
| Optimize a video from my phone | Picks videos from your storage and saves optimized copies. |
| Update engine | Updates yt-dlp to the newest (nightly) build. Not available while downloads are running. |
| Remove all | Clears the whole list and discards partial downloads. Saved files are kept. |

Each action shows how many videos it applies to, and is greyed out when that number is 0. Results appear as a short pop-up message, so you see them even when the list is scrolled down.

If a site stops working, try **More → Update engine** first. Sites change often, and yt-dlp is updated to keep up.

> **Installing a new build:** each CI build is signed with a new temporary debug key, so Android won't install it over the previous one. You have to uninstall first, which also clears the list and the updated engine. The app updates the engine again on its next start.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Downloading, and pausing or resuming when the connection changes |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keeping downloads alive in the background |
| `POST_NOTIFICATIONS` | The "downloads running" notification (Android 13+) |
| `WAKE_LOCK` | Keeping the CPU awake during downloads (released after 6 hours at most), and keeping Wi-Fi out of power-save mode while the screen is off |

No storage permission is needed. Files are written through MediaStore (`Download/VidGrab`) or a folder you grant through the system picker.

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/vidgrab/
│   ├── MainActivity.kt      # All UI (built in code, no XML layouts), link checking,
│   │                        # download queue, retry logic, saving, FFmpeg optimize
│   └── DownloadService.kt   # Foreground service + notification + wake lock
└── res/                     # Logo, launcher icons, launcher background color
.github/workflows/build.yml  # CI: builds the debug APK on every push
```

Main dependencies:

- `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp 2025.11.12, Python 3.12, and the QuickJS JavaScript engine that YouTube now requires)
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`

## How it works

1. **Checking a link** runs `yt-dlp --dump-single-json --no-playlist`. The app reads the `formats` list and builds the resolution menu from the video heights, keeping the largest reported size for each height.
2. **Downloading** runs yt-dlp with `--continue`, retries, a 20 s socket timeout, 10 MB HTTP chunks and 4 parallel fragments for HLS/DASH streams. Progress is printed in a fixed format of the app's own (`--progress-template`) and parsed by the app. The output goes to a private folder (`files/dl/<hash of url+resolution>`), so a retry or resume always finds its partial file. Video and audio are merged into MP4. Audio-only uses `-x --audio-format mp3`.
3. **Saving** copies the finished file into MediaStore Downloads or the chosen folder, then deletes the private copy.
4. **Optimizing** runs the bundled `ffmpeg -c copy` with `+genpts+discardcorrupt` and `-movflags +faststart` (MP4/MOV/M4V) and replaces the file only if the result looks sane.

## Known limitations

See [IMPROVEMENT_PLAN.md](IMPROVEMENT_PLAN.md) for the full list of known bugs and the roadmap. The main ones:

- Download state lives in the Activity. Rotation is handled, but closing the app from Recents during a download still breaks the link between the UI and the running download.
- The app is debug-build only and uses the placeholder `com.example.vidgrab` package name.
- The UI is English only.

## Legal

VidGrab is a general-purpose tool. Only download content you have the right to download, and respect each site's terms of service and the copyright of the content's owners.
