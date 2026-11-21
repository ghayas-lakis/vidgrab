# VidGrab

A small, self-contained Android app for downloading videos (or just their audio) from the web. Paste or share a link, pick a resolution, tap **Download**.

VidGrab bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg through the [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) library, so it works with any site yt-dlp supports. No server is involved: everything runs on the phone.

## Features

- **Automatic link checking.** Paste one or more links (one per line), use *Paste from clipboard*, or share a link to VidGrab from another app. Every new link is checked in the background and its available resolutions are listed.
- **Per-video resolution choice.** You can pick a specific height (with an approximate size), *Best available*, or *Audio only (MP3)*.
- **Batch downloads.** Up to two downloads run at the same time, and *Select all* ticks every item.
- **Pause, resume and stop.** *Pause* keeps the partial file and *Start* continues from it. *Stop* discards the partial file.
- **Survives bad connections.** When the connection drops or switches (for example mobile data to Wi-Fi), downloads stop at once and resume from the partial file on the new connection. If a resumed download stays far slower than it was before the interruption, it reconnects (at most twice). Progress, speed and ETA stay visible through interruptions. Readable messages are shown, such as "This video is private" or "Phone storage is full".
- **Wi-Fi only by default.** Downloads wait for Wi-Fi (a VPN running over Wi-Fi counts). Untick *Download on Wi-Fi only* to use mobile data as well. Checking links still works on mobile data, because it uses very little.
- **Background downloads.** A foreground service with a notification and a wake lock keeps downloads running when the app is in the background. Pressing *Back* during a download sends the app to the background instead of closing it.
- **Optimize.** FFmpeg remuxes the file without re-encoding. This fixes timestamps, drops corrupt packets and moves the MP4 index to the front ("faststart") so the video starts playing faster and seeks better. It can run automatically after each download, later on downloaded items, or on any video already on the phone (saved as a new "(optimized)" copy).
- **Choose where files go.** The default is `Download/VidGrab`. You can pick any folder through the system folder picker.
- **Engine updates.** *Update engine* downloads the latest yt-dlp build without updating the app.
- **List is remembered.** The list of items, their chosen resolution and their saved files are kept between launches.

## Requirements

| | |
|---|---|
| Android | 10 (API 29) or newer |
| CPU | ARM: `arm64-v8a` or `armeabi-v7a` (x86 emulators are not supported by this build) |
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

## How to use

1. Paste links into the box, tap **Paste from clipboard**, or use **Share → VidGrab** from a browser or app.
2. Wait for "Pick a resolution for each video", then choose a resolution in each card.
3. Tick the videos you want and tap **Download**. Each card also has its own **Start / Pause** and **Stop** buttons.
4. Finished files go to the folder shown on the **Save to:** button.

Other actions:

| Button | What it does |
|---|---|
| Remove completed | Clears finished items from the list. Files are kept. |
| Remove unfinished | Removes the *ticked* items that haven't finished, stopping them if they are running, and discards their partial files. |
| Remove all | Clears the whole list and discards partial downloads. Saved files are kept. |
| Delete files | Deletes the saved files of the ticked items. |
| Optimize files | Remuxes the saved files of the ticked items in place. |
| Optimize a video from my phone | Picks videos from your storage and saves optimized copies. |
| Update engine | Updates yt-dlp to the newest (nightly) build. |

If a site stops working, try **Update engine** first. Sites change often, and yt-dlp is updated to keep up.

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

- `io.github.junkfood02.youtubedl-android:library:0.17.4` (yt-dlp + Python runtime)
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4`

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
