# Uploading downloaded videos from the phone to a server

## Why

A video downloaded with the browser on the phone (e.g. at a friend's, from a link) should end up in the Library on
the computer without anyone having to remember it. The app notices the new file and asks right away in a
notification, but the upload itself happens only when the phone sees the server on the Wi-Fi, e.g. in the evening
after coming home.

## Tested

17.11.2026, Pixel 8a (Android 17) and jmpc: a video dropped into `Download/` (MediaStore, `IS_PENDING = 0`)
immediately produced the notification "Downloaded … Send to jmpc?". "Send and delete from phone" uploaded the file
(size matching, Polish characters in the name) to `torrents/Z telefonu/` and, after confirmation in the system
dialog, deleted it from the phone. Not tested: downloading in Chrome with the app closed, a phone restart, uploading
"after coming home".

The same day, the pop-up over the home screen: it appears a few seconds after the file is saved (WorkManager trigger,
app in the background); "Send and delete" with "All files access" uploaded the video and deleted it without the
system prompt; "No" closes the card and the notification; two files at once are shown one after another. Downloading
in Firefox: the card sits above the keyboard (`FLAG_ALT_FOCUSABLE_IM` puts the window under the keyboard layer,
`fitInsetsTypes` with `ime()` lifts it above; without this the card covered the keys). Chrome didn't open a page
with a LAN address (unexplained; Firefox did).

18.11.2026, Pixel 8a, app in the background (no open window; the process is kept alive by `RemoteRouteProvider`, so
a "fully closed" app is hard to get): a 37 MB video downloaded in Chrome ("Download" in the player menu) and in
Firefox ("Save file to device") produced the notification right after the download finished. `setForeground` from
the background works (foreground service `dataSync`, the "Uploading to jmpc" bar, 37 MB in a few seconds). The system
dialog for deleting a file owned by Chrome: "Allow MagneticPlayer to delete this video?" with a thumbnail; "Allow"
deletes it. A file with a name that already exists on the server ends up there as "name (2).mp4".

## How it works

1. **Detection** (`Uploads.kt`): a WorkManager job with the trigger
   `addContentUriTrigger(MediaStore.Video…, true)`. Works with the app closed. It fires once, so after every run it
   schedules the next one (`APPEND_OR_REPLACE`), and app start (`MainActivity.onCreate` → `Uploads.onAppStart`)
   schedules it in case it disappeared (`KEEP`). While the process is alive, a `ContentObserver` does the same scan
   (3 s after the last change).
2. **What counts:** a video with a `RELATIVE_PATH` starting with `Download/` (the camera in `DCIM/` is skipped),
   `IS_PENDING = 0` (the browser has finished), at least 1 MB, `DATE_ADDED` after the setting was turned on (the app
   doesn't ask about old files when it's turned on) and not older than 7 days. The app remembers files it has asked
   about by `_ID`. On Android 8–9 (no `RELATIVE_PATH`) it checks `DATA LIKE '%/Download/%'`.
3. **The question:** "Downloaded *name*. *size*. Send to *server*?" with the buttons "Send" and "Send and delete from
   phone". A notification can't have a checkbox, hence two buttons.
   With the "Display over other apps" permission (Settings → "Pop-up over the browser") the same question pops up as
   a card at the bottom of the screen over the browser (`UploadOverlay.kt`, a `TYPE_APPLICATION_OVERLAY` window with
   Compose, without focus), like a password manager asking to save a password: "No", "Send", "Send and delete", with
   a hint that the last one deletes the video from the phone. Several files at once go one after another. An
   untouched card disappears after 20 s, and the notification (silent at that point) stays.
4. **The job** (`UploadWorker`): unique per file (`upload:<MediaStore id>`), only on an unmetered network
   (`NetworkType.UNMETERED`). First a `ping` to the server. When it doesn't answer (the phone is away from home), the
   notification "Waiting for *server* — will upload when the phone is on its network" and another attempt in 15 min.
5. **Uploading:** `GET /api/upload/<id>` says how much the server already has, an empty `PUT` checks space (507) and
   consistency, then 64 MB chunks (`setFixedLengthStreamingMode`, the file streamed from the descriptor, not loaded
   into memory). A dropped connection leaves what arrived on the server, and the next attempt continues from there.
   During the upload there is a notification with progress and "Cancel" (foreground service `dataSync`, if the
   system allows it).
6. **Deleting:** the file belongs to the browser. With the optional "All files access" (`MANAGE_EXTERNAL_STORAGE`,
   Settings → "Delete without asking"; an app outside Play may request it) the app deletes it itself
   (`ContentResolver.delete`, and if that's refused, by path). Without it, it can only offer it for deletion. After
   the upload, the notification "Tap to delete from phone" opens the transparent `UploadDeleteActivity`, which opens
   the system dialog `MediaStore.createDeleteRequest` (Android 11+). On Android 10 and older the app says to delete it
   by hand.
7. **Server** (`server/mp_upload.py`): chunks go to `<folder>/.mp-upload/<id>.part`, and at the end a rename into the
   folder (on a collision "video (2).mkv"). The target folder is `upload_dir` from `config.json`, by default
   `Z telefonu` ("From phone") in the first library folder, so the video is in the Library right away. Neither Deluge
   nor Transmission is needed.

## Decisions

- **Permissions:** `READ_MEDIA_VIDEO` (Android 13+, together with `POST_NOTIFICATIONS`), and on 12 and older
  `READ_EXTERNAL_STORAGE`. The app asks for them only when the setting is turned on, and the setting is off by
  default. On Android 14, with "selected videos only", the app won't see new downloads. Full access is needed.
- **Target server:** chosen in Settings, otherwise the file server or the first one with `features.upload`. The app
  knows servers from pairing (the `Servers` list), so "in contact with server X" means X is on that list and answers
  `ping` (with the same `id`, so the token won't go to a foreign computer).
- **Waiting 15 min instead of `Result.retry()`:** WorkManager's backoff grows with every attempt (linear: 15, 30,
  45 min… up to 5 h), so after a day away from home the video would wait for hours. Instead, the worker finishes
  successfully and enqueues a new job with a 15 min delay. `retry()` stays for a connection dropped during the upload.
- **Upload id:** SHA-1 of a random per-installation salt, `_ID`, size, date and name, so it is stable per file
  (resuming hits the same `.part`) and differs between phones.
- **Foreground service:** Android 12+ doesn't allow starting one from the background, so `setForeground` may fail.
  Then the upload runs as a regular job (the system may stop it after ~10 min) and resumes in the next one.
- **The server remembers finished uploads for a week,** so a repeated request (the response got lost) gets "done"
  instead of creating a copy. Abandoned `.part` files are removed after a month.

## What we don't do

- **Intercepting downloads in the browser.** Android doesn't let an app peek at or take over another app's stream,
  and Chrome on the phone has no extensions. What remains is watching MediaStore after the download finishes.
- Uploading over mobile data (unmetered networks only) and from outside the LAN (a separate topic:
  Tailscale/WireGuard).
- Files from the camera (`DCIM/`) and folders other than `Download/`.
- Asking about files downloaded before the setting was turned on.

## To test on the phone

- The MediaStore trigger after a phone restart (WorkManager renews it at startup, but not tested).
- Uploading "after coming home" (downloaded outside the server's Wi-Fi).
