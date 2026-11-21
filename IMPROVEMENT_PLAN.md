# VidGrab: review findings and improvement plan

This review covers commit `8892a7e`. It is based on reading the code; nothing was built or run.

Line numbers refer to `app/src/main/java/com/example/vidgrab/MainActivity.kt` at that commit, unless another file is named.

> **Update:** the second round of changes fixed **M1**, **L1**, progress/speed/ETA vanishing after an interruption, most of **H3** (Stop and Remove now delete the partial files of every resolution, and orphaned folders are swept at startup), and the rotation part of **H1** (`configChanges`). It also added Wi-Fi-only downloading, *Remove unfinished*, a Wi-Fi lock, and reconnecting when a resumed download stays slow. Everything else below is still open.

---

## 1. Bugs and faults

### High severity

**H1. Download state belongs to the Activity, so recreating it breaks running downloads.**
The `items`, both executors and `svcOn` are fields of `MainActivity` (lines 79–83). Only the `running` set is static (line 93). The manifest has no `configChanges`, so **rotating the phone**, a dark-mode or language change, or swiping the app away from Recents during a download destroys the Activity and creates a new one. Then:
- The old worker threads keep updating views of the dead Activity, and that Activity leaks.
- The new Activity restores the items as "Paused". Tapping Start shows "Already running in the background" (line 703) and the card never shows progress.
- When the old download finishes, it marks `done` and stores the file URI on the *old* item. The new Activity still has `done=false`, so its next `persist()` (every `onPause`) overwrites that. The finished file is "forgotten": *Delete files* can't reach it, and *Start* downloads it again.
- When the old Activity's last download ends, its `refreshService()` calls `stopService()` (line 761), even if the new Activity has started downloads of its own. Those downloads then run without a foreground service and can be killed.
- `onDestroy` never shuts down `analyzer` or `downloader`.

*Fix:* move the queue, the `Item` state and the workers into `DownloadService`, or into a process-wide singleton the service owns. Expose the state to the UI as an observable model. The Activity should only render and send commands. A short-term fix is to add `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode"` to the activity, but that does not cover swiping away from Recents.

**H2. *Optimize files* can destroy the user's saved video** (lines 622–624).
The optimized temp file is written back with `openOutputStream(u, "wt")`, which truncates the original *before* the copy starts. If the copy then fails partway, the exception is swallowed, the temp file is deleted, and the user is left with a truncated, broken file. Storage running out is a likely cause, because this flow needs about 3× the video size. The original is lost.
*Fix:* write the result as a new document or MediaStore entry, and delete the original only after the copy succeeds. Or check free space first and keep the temp file until the write is confirmed.

**H3. Partial downloads leak in internal storage forever** (lines 532, 547, 731).
The partial-download folder is keyed by `url + resolution`. *Stop* and *Remove all* only delete the folder for the resolution selected *right now*. If you pause at 720p, switch to 1080p (the spinner is enabled while paused) and download that, the 720p partial stays in `files/dl/` forever. Nothing ever cleans up orphaned folders, and these can be gigabytes.
*Fix:* key the folder by URL only, or delete every folder for the URL. On startup, remove any `files/dl/*` folder that no item references.

### Medium severity

**M1. A network change can turn a recoverable interruption into a permanent failure** (lines 102–106, 844–855).
`onLost` kills every running yt-dlp process. The killed process fails with a cancellation or empty error, and `why()` classifies that as "Download failed (unknown reason)", which is not retried, *unless* `online()` happens to be false at that moment. If another network (for example mobile data) is already validated by then, the download gives up and asks the user to tap Download again.
*Fix:* set a per-item `killedForNetwork` flag in `onLost` and always treat that failure as retryable. Also catch `YoutubeDL.CanceledException` explicitly.

**M2. An interrupted optimize saves a duplicate or broken file** (lines 586, 831–838).
`optimize()` writes `name.opt.mp4` into the download folder. If the process dies during optimize, that file stays. On the next Start, yt-dlp reports "already downloaded", and both `name.mp4` and the half-written `name.opt.mp4` pass the filter and get saved.
*Fix:* write ffmpeg output to `cacheDir`, or exclude `*.opt.*` in the filter at line 832.

**M3. *Delete files* reports success even when nothing was deleted** (lines 560–575).
Errors are swallowed, and the item is then reset to "Files deleted". After a reinstall or *Clear data*, the app no longer owns its MediaStore entries, so `delete` throws `RecoverableSecurityException` and the file stays on disk.
*Fix:* count failures and report them. For MediaStore, use `MediaStore.createDeleteRequest` on API 30+, or handle `RecoverableSecurityException` on 29.

**M4. Saving is not atomic** (lines 871–890).
The MediaStore entry is inserted without `IS_PENDING`. If the copy fails, a 0-byte or truncated file is left visible in `Download/VidGrab`. The SAF branch has the same problem. With several output files, a failure after the first copy means the earlier ones are saved again on retry.
*Fix:* insert with `IS_PENDING=1`, clear it after the copy, and delete the entry on failure. Track which files were already saved.

**M5. Actions are possible before the engine is ready.**
Restored items can be started, and *Optimize a video from my phone* can run, before `YoutubeDL.init` and `FFmpeg.init` finish (lines 268–281). They fail with "Download failed (unknown reason)" or "Could not optimize". If init itself fails, there is no way to retry it.
*Fix:* disable those actions until `ready`, or queue them. Add a retry for a failed init.

**M6. The engine can be updated during active downloads** (line 893).
Replacing yt-dlp while processes are using it can break those downloads. Updates also use the `NIGHTLY` channel, which can bring in regressions.
*Fix:* disable *Update engine* while any item is busy. Default to `STABLE` and offer nightly as an option.

**M7. A second Activity instance can appear from the share sheet** (manifest, `launchMode="singleTop"`).
A share usually starts the Activity inside the *sending* app's task, and `singleTop` doesn't stop that. You can end up with two VidGrab instances, each with its own list and workers, overwriting each other's saved state. This has the same root cause as H1.
*Fix:* use `singleTask`, or a small share-receiver Activity that forwards the link and finishes. Fixing H1 also fixes this.

**M8. Optimizing blocks link checking.**
Optimize jobs run on the same single-thread `analyzer` as link checks (lines 612, 664). Pasting a link while a large file is optimizing shows nothing for minutes.
*Fix:* give optimizing its own executor.

### Low severity

| # | Where | Issue |
|---|---|---|
| L1 | line 693 | The message says *Tap "Check links" first*, but that button no longer exists because checking is automatic. |
| L2 | lines 785–789 | *Best available* prefers `ext=mp4`, so on YouTube it can be lower than the top resolution listed (4K is usually VP9 or AV1). The size estimate covers only the video stream, so the real file is larger. Consider `-S res:<h>,ext:mp4:m4a`. |
| L3 | line 452 | `removeFromInput` removes every line *containing* the URL, so `…/abc` also removes `…/abc?t=5`. |
| L4 | lines 325–345 | Error matching is plain substring search on raw output. `"ssl"`, `"login"` and similar can match unrelated text, such as a video ID or title. |
| L5 | line 373 | `takePersistableUriPermission` is unguarded and can crash. The previous folder's permission is never released. The folder label shows raw `primary:Download/X`. |
| L6 | lines 201, 264 | The EditText has no ID, so pasted text is lost on rotation. The share intent is re-applied when the Activity is recreated. |
| L7 | lines 403–404 | `--no-playlist` does nothing for a pure playlist URL. The check dumps the whole playlist, which is slow, and the download fetches every entry. |
| L8 | line 827 | The progress bar goes 0→100% twice (video stream, then audio stream). |
| L9 | line 99 | `onCapabilitiesChanged` fires on every signal-strength change and re-runs `autoCheck`. It's cheap, but links that hit network errors are retried far too often. |
| L10 | line 266 | The notification permission is requested on every launch, and the answer is never checked. |
| L11 | lines 399–439 | Problems are reported as "Link *n*", counted within the current batch. That number doesn't match anything the user can see. |
| L12 | lines 70, 840 | `Item.files` is a plain list that is written on a worker thread and read on the UI thread. |

### Repository and build

- `VidGrab5.zip` is an exact copy of the source tree, apart from line endings. It adds 470 KB to the repo and goes out of date with every commit. **Delete it.**
- There's no `.gitignore`, so `build/`, `.gradle/`, `local.properties` and `*.iml` can easily get committed.
- There's no Gradle wrapper, so builds depend on whichever Gradle is installed. Add `gradlew` with a pinned 8.7.
- There's no release build type, no signing config, `versionCode = 1` is never bumped, and the package name is `com.example.*` (Google Play rejects that).
- CI builds debug APKs only. It runs no lint or tests and builds on every push to any branch.
- There are no unit tests at all.
- The `x86_64` ABI is filtered out, so the app can't run on a standard emulator. That's fine for release, but awkward for development.
- `MainActivity.kt` is about 900 lines that handle the UI, networking, file I/O, process management and persistence together.

---

## 2. Improvement plan

Phases are ordered by risk reduction per unit of effort. Each phase can ship on its own.

### Phase 0: repo hygiene (about 1 hour)
1. Delete `VidGrab5.zip` and add a standard Android `.gitignore`.
2. Add the Gradle wrapper (8.7) and switch CI to `./gradlew`.
3. Fix the stale "Check links" message (L1).

### Phase 1: data-loss and correctness fixes (1–2 days)
1. **H2:** make *Optimize files* non-destructive (write a new copy, then swap).
2. **M4:** make saving atomic with `IS_PENDING` and clean up on failure.
3. **M2:** move ffmpeg output to `cacheDir` and filter out `*.opt.*`.
4. **H3:** key the download folder by URL and sweep orphaned folders on startup.
5. **M1:** always treat a network-triggered kill as retryable, and handle `CanceledException`.
6. **M3:** report failed deletes and use `createDeleteRequest` on API 30+.
7. **M5, M6:** gate actions on `ready`, block updates while downloading, and default to the stable channel.
8. Stopgap for **H1:** add `configChanges` to the activity in the manifest.

### Phase 2: architecture (3–5 days)
1. Move downloads into the service, which fixes **H1** and **M7** properly:
   - A `DownloadManager` singleton (or a bound `DownloadService`) owns the queue, item state, executors and persistence.
   - Item state becomes immutable data classes exposed through `StateFlow`. The Activity observes them and renders.
   - The service starts and stops itself based on the queue, so `svcOn` and the Activity no longer need to track it.
   - Show per-download progress in the notification, with Pause and Stop actions.
2. Store items in Room or a single JSON file instead of a `SharedPreferences` string, and keep each item's resolution choice and saved URIs there.
3. Split `MainActivity.kt` into layers: `ui/`, `download/` (yt-dlp wrapper, request builder, error classifier), `storage/` (save and delete), `media/` (ffmpeg optimize).
4. Optionally migrate the UI to Jetpack Compose. The UI is already built in code, so the port is straightforward and gets you theming and dark mode.

### Phase 3: tests and CI (1–2 days, after Phase 2)
1. Unit tests for the pure logic that Phase 2 separates out: the error classifier `why()`, format and label building from a sample `--dump-single-json`, `buildRequest` options, and URL extraction.
2. CI: run `lint` and `test`, build only on `main` and pull requests, and attach release APKs to GitHub Releases for tags.
3. Add a release build type with signing from CI secrets and `versionCode` taken from the run number or tag. Rename the package away from `com.example`.

### Phase 4: features and UX (as wanted)
- Better format selection using `-S`, with codec choice (H.264 for compatibility versus best quality) and real total sizes (video + audio).
- Playlist support: list the entries and let the user tick them.
- Thumbnails in each card (the `thumbnail` field from the JSON dump).
- A setting for concurrent downloads (currently fixed at 2) and a Wi-Fi-only option.
- Subtitles and embedded metadata or thumbnails (`--embed-subs`, `--embed-metadata`, `--embed-thumbnail`).
- Cookie or login support for sites that require sign-in.
- Proper dark mode, localization (move strings into `strings.xml`), and accessibility (content descriptions, larger touch targets).
- "Open" or "Share" buttons on finished items.
