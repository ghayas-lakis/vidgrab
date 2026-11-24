# VidGrab

A small, self-contained Android app for downloading videos (or just their audio) from the web. Paste or share a link, pick a resolution, tap **Download**.

VidGrab bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg through the [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) library, so it works with any site yt-dlp supports. No server is involved: everything runs on the phone.

## Features

- **Private browser with a video finder.** Tap **Browse** to open any site (TikTok, Dailymotion, YouTube, Instagram, ...) inside VidGrab. When a page shows a video, a round **N videos** button appears. Tap it to see every video found, tick the ones you want and tap **Download**. The browser has only back, forward, the address bar (type an address or search with DuckDuckGo), reload and a small menu (*Downloads*, *Desktop site*, *Clear videos found*, *Close and erase browsing*).
- **Collect a whole series by swiping.** Every video you watch is added to the list in the order you watched it, so swiping quickly through the episodes of a TikTok series collects them all. Ads are left unticked: videos marked "Sponsored" / "Ad" (in several languages), and, once most of the list comes from one account, videos from other accounts. Untick any episode you don't want. Links to videos on the page (for example a series or playlist page) are listed below, unticked.
- **Choose by length or size.** The list of videos found shows each one's length (and size, when known) and has two filters: *length* (for example 5 min and longer, Shorts only, or any number of minutes) and *size* (for example 5 MB and bigger). What doesn't pass is hidden and never ticked, the round button counts only what passes, and *Tick all* ticks only that. The filter is remembered. Lengths come from the page itself (YouTube, TikTok, Dailymotion, Twitch, ...), from the stream (HLS, DASH, MP4), or from the site (Dailymotion, Vimeo, YouTube). Videos whose length still isn't known are listed separately, unticked, with a *Check now* line that asks yt-dlp.
- **Merge episodes into one video.** With two or more videos ticked, VidGrab asks whether to merge them. *Merge* downloads them all, joins them into one video in the order of the list, saves it, and deletes the separate videos. When the videos share a format (the usual case for one series) this takes seconds and doesn't re-encode; otherwise they are fitted to the first one's size and re-encoded, which is slower. If merging fails, every video is saved on its own instead, so nothing is lost. A video you remove from the list leaves the merge, and the rest are still merged.
- **Nothing is kept.** The browser keeps no history, searches, cookies, logins, site storage, cache or form data: all of it is erased when you close the browser (and again when it opens, in case VidGrab was killed while it was open). The address bar asks the keyboard not to learn what you type, and third-party cookies are blocked. Links to "open in the app" are not followed.
- **Proxy / VPN.** VidGrab has no VPN servers of its own: free VPNs that can be trusted come as separate apps (for example Proton VPN's free plan), and any VPN app you turn on covers VidGrab and its browser too. **Settings → Proxy / VPN** also takes a proxy (`http`, `https`, `socks4` or `socks5`), used for link checks, downloads and the browser.
- **Add as many links as you like.** Paste them all at once (one per line), tap *Paste* as often as you want, or share links to VidGrab from other apps. New links are checked in the background, 3 at a time, and each becomes a card with its available qualities.
- **Download all, some or one.** With nothing ticked, the bottom button says **Download all (N)** and downloads every video that isn't downloaded yet. Tick some cards (or *Select all*) to download only those, or use a card's own **Start** button for just that one. A line above the list shows how many are active, done and failed.
- **Downloads at the same time.** Settings has a **−  2  +** control (1 to 5). The rest wait as *Queued...* and start as soon as one finishes. More at once helps with many small videos. It doesn't make one big video faster, because they share your connection, and some sites slow down or block above about 3.
- **Quality per video, with a default.** Each card has its own quality picker: a specific height (with an approximate size), *Best available*, or *Audio only (MP3)*. **Settings → Quality for new links** sets the one new cards start on: that height or the nearest one below it. *Also set N ticked* applies it to the ticked cards in one go.
- **Pause, resume and stop.** *Pause* keeps the partial file and *Start* continues from it. *Stop* discards the partial file.
- **Survives bad connections.** When the connection drops or switches (for example mobile data to Wi-Fi), downloads stop at once and resume from the partial file on the new connection. If a resumed download stays far slower than it was before the interruption, it reconnects (at most twice). Progress, speed and ETA stay visible through interruptions. Readable messages are shown, such as "This video is private" or "Phone storage is full".
- **Wi-Fi only by default.** Downloads wait for Wi-Fi (a VPN running over Wi-Fi counts). Untick *Download on Wi-Fi only* to use mobile data as well. Checking links still works on mobile data, because it uses very little.
- **Background downloads.** A foreground service with a wake lock keeps downloads running when the app is in the background, and even after you close it or swipe it away from Recents. Opening VidGrab again shows the same downloads, still running. Pressing *Back* during a download sends the app to the background instead of closing it.
- **Notifications.** Each download has its own notification with progress, speed, ETA and **Pause** / **Stop** buttons. When it ends you get *Downloaded* (tap it to open the file), *Paused* (with **Resume**) or *Download failed* (with **Retry**).
- **Playlists and channels.** Paste a playlist link and VidGrab lists its videos. Untick the ones you don't want and tap *Add ticked*: each becomes its own card. A link to one video inside a playlist still gives just that video.
- **Sign in to sites.** *More → Sign in to a site* opens the site's login page inside VidGrab. After you sign in and tap *Done*, links and downloads use that login: for private or members-only videos, or when a site says "Sign in to confirm you're not a bot". The login stays on the phone; *More → Sign out of all sites* forgets it.
- **Optimize.** FFmpeg remuxes the file without re-encoding. This fixes timestamps, drops corrupt packets and moves the MP4 index to the front ("faststart") so the video starts playing faster and seeks better. It can run automatically after each download, later on downloaded items, or on any video already on the phone (saved as a new "(optimized)" copy).
- **Choose where files go.** The default is `Download/VidGrab`. You can pick any folder through the system folder picker.
- **Engine updates.** The app checks for a new yt-dlp once a day at startup (about 3 MB), because sites change often and an outdated engine stops working with them. *More → Update engine* updates it on demand.
- **Error details.** Tap a red error message, or the status bubble after a failed link check, to see the full error text and the engine version, with a *Copy* button.
- **List is remembered.** The list of items, their chosen resolution and their saved files are kept between launches.

## Requirements

| | |
|---|---|
| Android | 10 (API 29) or newer |
| CPU | 64-bit ARM (`arm64-v8a`): nearly every phone from the last 8 years. For an old 32-bit phone, build with `-Pvidgrab.abis=armeabi-v7a`; for an x86_64 emulator, with `-Pvidgrab.abis=x86_64`. |
| Storage | Free space of roughly **2× the video size**. Downloads are assembled in app storage and then copied to your folder, and optimizing needs one more temporary copy. |

## Installing

Every push to GitHub builds the APK through GitHub Actions.

1. Open the repository's **Actions** tab and select the latest successful **Build APK** run.
2. Download the **VidGrab-apk** artifact and unzip it. It holds one file, `app-debug.apk`.
3. Copy the `.apk` to your phone and open it. You may need to allow "Install unknown apps" for your file manager or browser.

### The signing key (installing over the previous version)

Android only installs a new build over the old one when both are signed with the same key. CI signs with VidGrab's fixed key, which is kept in a repository secret, never in the code:

1. On GitHub: **Settings → Secrets and variables → Actions → New repository secret**.
2. Name: `VIDGRAB_KEYSTORE`. Value: the whole content of `vidgrab-signing.base64.txt` (the key file, base64 encoded).
3. Keep `vidgrab-signing.p12` somewhere safe: a build signed with another key needs an uninstall first.

Without the secret, the build still works but uses a temporary key, so each new build needs an uninstall first (which also clears the list and the updated engine). The build shows a warning when that happens.

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

Every push, and a run every Monday (sites change even when the code doesn't), runs two GitHub Actions jobs:

- **build** compiles the APK. On failure, Kotlin errors show up as annotations on the run's page.
- **e2e** starts an Android 13 emulator, installs the app and uses it for real, with the bundled yt-dlp, Python and FFmpeg and a real network (`app/src/androidTest`, run by `ci/e2e.sh`):
  - `EndToEndTest` (must pass): pastes a link to [testdata/vidgrab-test.mp4](testdata/vidgrab-test.mp4), downloads it, checks the saved file was optimized (its MP4 index moved to the front), runs *Optimize ticked files*, then *Delete ticked videos* and checks the file is gone. It also downloads *Audio only (MP3)* and checks the result is an MP3, checks *Remove ticked unfinished*, adds a playlist link ([testdata/playlist.rss](testdata/playlist.rss)) and checks its two videos become cards, pastes two links at once and uses *Download all* with *Downloads at the same time* set to 1 (checking that both finish and never more than one runs), checks that *Quality for new links* sets new cards, and closes the screen during a download to check that a new screen picks up the same download and its result.
  - `YouTubeTest` (warning only): downloads a short YouTube video. YouTube often blocks CI's data-center IPs, so a failure there is reported as a warning rather than failing the run.

Test results, the HTML report and logcat are in the run's **e2e-results** artifact. The tests drive the app through a small set of hooks in `MainActivity` (see "UI TEST CONTRACT" there); keep them working when changing the UI.

To run the tests locally on a connected device or emulator: `gradle connectedDebugAndroidTest` (add `-Pvidgrab.abis=x86_64` for an x86_64 emulator).

## How to use

1. Paste links into the box, tap **Paste**, or use **Share → VidGrab** from a browser or app.
2. Each link gets a card with its thumbnail (with the video length), title, site and uploader. Pick a quality in the pill under the title.
3. Tick the videos you want (tap the circle on the thumbnail, or anywhere on the card) and tap **Download (N)** in the bottom bar. With nothing ticked, the button is **Download all (N)**. Each card also has its own **Start / Pause** button and a round **Stop** button. The status line is blue while working, amber while waiting, red on errors and green when done.
4. Finished files go to the folder shown in **Settings → Save to** (tap the row to change it). Finished cards switch to **Open** and a round **Share** button.

The app follows the system's light or dark theme.

### Downloading from the browser

1. Tap **Browse** (next to *Paste*) and open a site from the start page, or type an address.
2. Play a video. The **N videos** button appears at the bottom right. For a series, swipe through the episodes: each one you watch is added.
3. Tap the button. Watched videos are ticked, except likely ads (shown in amber). Tap a row to tick or untick it.
4. To list only long videos (or only Shorts), tap **Any length** at the top of the list and choose, for example *5 min and longer*. **Any size** does the same in MB.
5. Tap **Download (N)**. With two or more, choose **Merge** (one video, separate ones deleted afterwards) or **Keep separate**.
6. The videos are added to VidGrab's list with the default quality (**Settings → Quality for new links**) and start downloading, *Downloads at the same time* at a time. Open the list from the browser's menu (**Downloads**) to see each one's speed and time left. Closing the browser doesn't stop them.

Everything else is in the **More** sheet in the bottom bar:

| Action | What it does |
|---|---|
| Remove completed | Clears finished items from the list. Files are kept. |
| Remove ticked unfinished | Removes the *ticked* items that haven't finished, stopping them if they are running, and discards their partial files. |
| Delete ticked videos | Deletes the *ticked* videos, finished or not. Saved files are deleted from the phone, unfinished downloads are stopped and discarded, and the cards are removed. |
| Optimize ticked files | Remuxes the saved files of the ticked items in place. |
| Optimize a video from my phone | Picks videos from your storage and saves optimized copies. |
| Update engine | Updates yt-dlp to the newest (nightly) build. Not available while downloads are running. |
| Sign in to a site | Opens a site's login page (YouTube, Instagram, Facebook, X, TikTok, Vimeo or any other) so links and downloads can use your login. |
| Sign out of all sites | Forgets every login VidGrab saved. |
| Remove all | Clears the whole list and discards partial downloads. Saved files are kept. |

Each action shows how many videos it applies to, and is greyed out when that number is 0. Results appear as a short pop-up message, so you see them even when the list is scrolled down.

If a site stops working, try **More → Update engine** first. Sites change often, and yt-dlp is updated to keep up.

> **Installing a new build:** with the signing key set up (see *Installing*), a new build installs over the old one and keeps your list. Version 1.2 changed the app's package name, so it installs as a new app next to older builds: uninstall the old *VidGrab* once.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Downloading, and pausing or resuming when the connection changes |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keeping downloads alive in the background |
| `POST_NOTIFICATIONS` | Download progress and "Downloaded" notifications (Android 13+) |
| `WAKE_LOCK` | Keeping the CPU awake during downloads (released after 6 hours at most), and keeping Wi-Fi out of power-save mode while the screen is off |

No storage permission is needed. Files are written through MediaStore (`Download/VidGrab`) or a folder you grant through the system picker.

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── java/io/github/ghayaslakis/vidgrab/
│   ├── MainActivity.kt      # All UI (built in code, no XML layouts), link checking,
│   │                        # download queue, retry logic, saving, FFmpeg optimize
│   ├── DownloadService.kt   # Foreground service + wake lock + Wi-Fi lock
│   ├── Notifier.kt          # Per-download notifications and their buttons
│   ├── Browser.kt          # Private browser and its list of videos found (assets/finder.js)
│   ├── Lengths.kt          # Video lengths and sizes for the browser's filters
│   ├── Merge.kt            # Joining videos into one
│   └── SignIn.kt            # In-app sign-in page and the cookies file for yt-dlp
└── res/                     # Logo, launcher icons, launcher background color
app/src/androidTest/         # End-to-end tests (run on an emulator by CI)
ci/                          # build.sh, e2e.sh, annotate.py (used by the workflow)
testdata/                    # Test video and test playlist for the end-to-end tests
.github/workflows/build.yml  # CI: build + emulator tests on every push and every Monday
```

Main dependencies:

- `io.github.junkfood02.youtubedl-android:library:0.18.1` (yt-dlp 2025.11.12, Python 3.12, and the QuickJS JavaScript engine that YouTube now requires)
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`

## How it works

1. **Checking a link** runs `yt-dlp --dump-single-json --no-playlist --flat-playlist`. The app reads the `formats` list and builds the resolution menu from the video heights, keeping the largest reported size for each height. For a playlist, `--flat-playlist` lists its videos without checking each one; the ones you pick are then checked one by one.
2. **Downloading** runs yt-dlp with `--continue`, retries, a 20 s socket timeout, 10 MB HTTP chunks and 4 parallel fragments for HLS/DASH streams. Progress is printed in a fixed format of the app's own (`--progress-template`) and parsed by the app. The output goes to a private folder (`files/dl/<hash of url+resolution>`), so a retry or resume always finds its partial file. Video and audio are merged into MP4. Audio-only uses `-x --audio-format mp3`.
3. **Saving** copies the finished file into MediaStore Downloads or the chosen folder, then deletes the private copy.
4. **Signing in** saves the in-app browser's cookies for the sites the login visited, as a Netscape `cookies.txt` in the app's private storage. Each yt-dlp run gets its own copy through `--cookies`.
5. **Optimizing** runs the bundled `ffmpeg -c copy` with `+genpts+discardcorrupt` and `-movflags +faststart` (MP4/MOV/M4V) and replaces the file only if the result looks sane.

## Known limitations

See [IMPROVEMENT_PLAN.md](IMPROVEMENT_PLAN.md) for the full list of known bugs and the roadmap. The main ones:

- If Android kills the whole app (rare while a download runs, because of the foreground service), running downloads stop. The list and partial files are kept, and *Start* continues them.
- Google may refuse to sign in inside an app's browser ("This browser or app may not be secure"), so signing in to YouTube can fail even when other sites work.
- Builds are debug builds, signed with VidGrab's own key (not for the Play Store).
- The UI is English only.

## Legal

VidGrab is a general-purpose tool. Only download content you have the right to download, and respect each site's terms of service and the copyright of the content's owners.
