# Architecture

```
 phone (app)                            computer (Linux Mint)
 ┌───────────────────┐   HTTP :8765    ┌───────────────────────────────┐
 │ MainActivity      │ ──────────────▶ │ magneticplayer_agent.py       │
 │ MagnetActivity    │   Bearer token  │  ├─ backend: deluge-web JSON  │──▶ deluged (systemd --user)
 │ PlayerActivity    │ ◀────────────── │  │   (or Transmission RPC)    │
 │  (ExoPlayer)      │  Range stream   │  ├─ mpv JSON IPC              │──▶ mpv on screen
 │ Updater           │                 │  ├─ mp_library (folders)      │
 └───────────────────┘                 │  ├─ mp_subs (subliminal/venv) │──▶ OpenSubtitles, BSPlayer…
          │  UDP :9/:7 magic packet    │  ├─ mp_jobs (jobs, actions)   │
          └──────────────────────────▶ │  └─ SleepGuard (inhibit)      │──▶ systemd-inhibit / suspend
                 Wake-on-LAN           └───────────────────────────────┘
```

## Agent (`server/`)

A single Python process (`ThreadingHTTPServer`) with no external dependencies apart from subliminal. It runs as a
`systemd --user` service in the user's session, because it needs the user's screen (mpv) and folders. On Windows
(light install, `install.ps1`) there is no service; it starts from `HKCU\…\Run` at logon, and mpv listens on a named
pipe instead of a unix socket.

| file | responsibility |
|---|---|
| `magneticplayer_agent.py` | HTTP API, authorization, Range streaming, Deluge and Transmission backends, mpv control, `SleepGuard`, computing "how much can be watched", video durations |
| `mp_jobs.py` | history of jobs sent from the phone and the after-download action (`none` / `stop` / `suspend`) |
| `mp_library.py` | library folders, listing only video and audio files, subtitles next to videos, protection against escaping the folder (`safe_join`) |
| `mp_subs.py` | searching and downloading subtitles via subliminal (lazy import from the venv) |
| `mp_devices.py` | paired devices (tokens stored as SHA-256), pairing codes, HMAC tickets for a single file; `devices.json` is re-read when its mtime changes (the CLI is a separate process) |
| `mp_cast.py` | Chromecast via pychromecast: discovery, listening to the state of all devices (any sender), playback and control for the web page and the Remote |
| `mp_announce.py` | broadcasting "what's playing" between remotes (UDP multicast `239.255.77.77:47700`): listening for the web page (`/api/announce`), announcing this server's mpv, answering "who" |
| `mp_renderers.py` | who the Chromecasts are (`product_name` from `eureka_info`), codec profiles per model (`CAST_PROFILES`) and the `renderers.json` registry; described in `docs/devices.md` |
| `mp_access.py` | access requests from the home network: a request without a token, the list and a household member's decision, handing a pairing code to the requester |
| `mp_install.py` | installing the agent on another computer: join code, the `/i/<code>` script (templates `join.sh`, `join.ps1`, shared with the phone), the bundle, registering the new server with a pairing code for the phone |
| `mp_upload.py` | receiving videos from the phone (`/api/upload/<id>`): resumable chunks in `.mp-upload/`, free space, name collisions, the `upload_dir` folder or `Z telefonu` ("From phone") in the library |
| `mp_selfupdate.py` | the agent updates itself from signed GitHub releases (ECDSA P-256 verified in pure Python), at a quiet moment, through `self-update.sh` with a rollback |
| `mp_tasks.py` | background file tasks with a preview: planners (free up a disk, tidy up by recognition, duplicates, shrinking by storage profiles, deleting to the trash) and one worker; moving through the torrent client when the torrent is still in it; `.mp-trash/` per library folder |
| `mp_mcp.py` | MCP server for an AI assistant in the LAN (Streamable HTTP, tools over the library and tasks) and the OAuth 2.1 endpoints whose approval is an access request on the phone |
| `mp_i18n.py` | language of user-facing messages: `T("English", "Polski")` based on the current request's `Accept-Language` (a per-thread value set at the start of `dispatch`); `bind(fn)` carries the language into background threads; the CLI (`--pair`, `--join`) uses `LC_ALL`/`LC_MESSAGES`/`LANG`. Install scripts have their own `say`/`Say` (Polish with a Polish locale / Windows UI language) |
| `mp_platform.py` | everything OS-specific: graphical session, unlocking, suspend, network interface, wake assessment, sending WoL, the mpv path (on Windows `mpv\mpv.exe` next to the agent). Another OS is added here; whatever it can't do, the agent doesn't report in `features`/`power` |

### Multiple servers

Plan and decisions: [plan-multi-server.md](plan-multi-server.md), mockups: `ux-wiele-serwerow.html`.
- A server has an `id` (random, in `config.json`) and a `name`. The app keeps a list of servers and checks the `id`
  with `ping` on every request, so it won't send a token to a different computer that got the old address.
- "Asleep" in the app header: only after two failed `ping`s in a row (the second attempt after 1 s), also right after
  opening the app, when "…" is shown until then. The app waits 3 s for a connection (a sleeping computer doesn't
  answer at all; after a longer pause the phone's Wi-Fi can be slow) and 5 s for a response. The agent doesn't call
  `stat()` through `DirEntry` from `os.scandir`, because in Python 3.10 it doesn't release the GIL: an old USB disk
  spinning up froze the whole agent for several seconds, `/api/ping` included.
- Roles: file server (`features.torrent`/`library`) and screen (`features.player`). One computer can have both.
- A video from server A on screen B: the app gets a ticket from A (`/api/media/ticket`) and sends B `player/open`
  with the URL. mpv on B pulls the video and subtitles over HTTP. The Remote in the app saves the position of such
  a video.

### Chromecast and the phone as a source

- **Chromecast** (Google Cast, Google's default receiver, no custom receiver app): the app discovers devices via
  MediaRouter (`Cast.kt`), shows them in "Where to play?" next to mpv screens and sends the ticket URL with
  subtitles as WebVTT tracks (`/sub?…&vtt=1`, CORS on the agent). MKV is sent as `video/mp4`, and the receiver
  detects the container from the content. What plays depends on the Chromecast's codecs (H.264/HEVC, AAC; AC3/DTS
  varies). Control: the Remote (see below), the notification and the Cast SDK full screen (`CastControlsActivity`).
  The phone's volume buttons change the Chromecast's volume. A Chromecast with fixed volume (e.g. controlled by the
  TV over HDMI-CEC, `volumeHandling == FIXED`) shows a hint instead of the buttons. The app saves the position
  every 5 s.
- **Phone as a file server** (`PhoneShare.kt`): when the server with the video doesn't respond (e.g. when visiting
  someone) and the whole video is on the phone ("Download to phone"), the app serves it over HTTP on the Wi-Fi with
  a random secret in the path: Range, CORS, subtitles in the original format (for mpv) and as WebVTT (for
  Chromecast). It reads from MediaCache. It runs in the foreground service `ShareService` (notification with "Stop",
  WifiLock + WakeLock). It ends after 20 min without requests or when the Chromecast session ends. An mpv screen
  that doesn't have the file gets the same address (`player/open` with a URL).
- Source choice (`Screens.source`): the server responds or the phone doesn't have the whole video → a ticket from
  the server (with waking); otherwise the phone.
- **Chromecast version** (`server/mp_media.py`): `ffprobe` assesses the file (ok / maybe / no), and "Prepare" creates
  `.mp-cast/<name>.cast.mp4`. H.264 video is copied, anything else is encoded with the best working hardware encoder,
  found by a test encode at startup (NVENC, VideoToolbox, VAAPI, QSV, V4L2, and x264 as the last resort). Audio goes
  to AAC. A ticket with `variant: "cast"` points to this version. It is deleted together with the torrent ("Remove
  with files").
- **DLNA TVs** (`Dlna.kt`): SSDP M-SEARCH (MediaRenderer), then SOAP AVTransport: SetAVTransportURI with DIDL,
  Play, Seek, Pause, Stop. Volume via RenderingControl. Subtitles for Samsung: `sec:CaptionInfoEx` in the DIDL plus
  the `CaptionInfo.sec` header in the video response (the agent with `&cap=`, the phone on its own). The TV gets
  the original, without conversion.
  - Samsung seeks only when `protocolInfo` in the DIDL and the `contentFeatures.dlna.org` header contain
    `DLNA.ORG_OP=01` (otherwise it offers only Pause/Stop and answers Seek with 501). MKV must have the type
    `video/x-mkv`. The agent and the phone recognize a DLNA client by the `getcontentFeatures.dlna.org` header or
    "DLNA" in the User-Agent.
  - On the first connection from a new address (phone, PC) the Samsung asks for permission on screen. Until then it
    stays in TRANSITIONING.
- **Remote** (`Remote.kt`, `MainActivity.PilotTab`): one `Remote` layer with three variants (`MpvRemote`,
  `CastRemote`, `DlnaRemote`) and one button layout. At the top are target chips: the screen (▾ picks another one),
  Chromecast and TV when something is playing on them. Idle Chromecasts and DLNA TVs (SSDP every 30 s) discovered
  from the phone also get a chip, with a hint that videos are sent from the Library ("To screen"). Sending a video
  switches the Remote to that target (`Pilot.started`). Whatever the target can't do is hidden: tracks, subtitles
  and full screen are only for mpv, and volume doesn't appear with fixed volume. The phone's volume buttons control
  the Remote's target (mpv only on the Remote tab). While something is playing, the Remote keeps the screen on
  (setting "Remote keeps the screen on", on by default).
  "Stop" on someone else's app on a Chromecast (Spotify, YouTube) closes it, so it asks first: in the app with a
  dialog, in the notification with a second tap within 5 s (setting "Ask before closing other apps on Chromecast",
  on by default). The Remote also shows Chromecasts with an open app that isn't playing anything (e.g. Spotify left
  after listening still shows the cover art), with a "Close" button (`CastNet.openIdle`).
  - DLNA after the app is killed: what was sent is stored in the `dlna` SharedPreferences. At startup the app
    queries the TV (GetTransportInfo, GetMediaInfo), and if it is still playing the same URL, the Remote and the
    notification come back.
  - Starting DLNA from a saved position: Seek after entering PLAYING, a position check and up to 4 attempts
    (right after starting, the Samsung may ignore Seek). Log tag `MpDlna`.
  - Chromecast from the app (Cast SDK on the phone) and from the web page (pychromecast in the agent) are separate
    senders: the Remote in the app doesn't see a video started from the web page and vice versa.
- **Notification and volume outside the app** (`RemoteSession.kt`): every playing target (mpv screen, DLNA TV,
  Chromecast controlled without the Cast SDK) has its own media3 session (`RemotePlayer(kind)` on
  `SimpleBasePlayer`, DeviceInfo REMOTE 0–100) and its own notification (id 2001/2002/2003; 1001 belongs to the
  phone's player) in `RemoteSessionService`. `RemoteSessionService.start(ctx, kind)` adds the session right away
  (`addSession`), because otherwise the notification appears only after a controller connects. A session goes away
  after ~10 s of silence, the service with the last one. A Chromecast with the Cast SDK has the SDK's notification.
  Android 17 shows one card per Chromecast anyway: it merges ours with the system Cast card.
- **Chromecast from the browser**: the web client has no Cast SDK (it requires HTTPS), so the agent controls it
  (`mp_cast.py`, pychromecast in the venv), and the video is served by the agent at its LAN address. The agent
  listens to all Chromecasts, so the Remote in the app (without its own Cast session) and the web page also see
  what someone else started.
- **Will it play on Chromecast**: the agent knows the model of every Chromecast (`mp_renderers.py`) and assesses the
  file for each one separately (`cast_devices`). "Prepare" is offered only when one of them has trouble. How to ask
  devices for their model and capabilities: `docs/devices.md`.

### File reference (`ref`)

Playback and subtitle endpoints accept one of these forms:
- `{"torrent_id": "<hash>", "file_index": N}`: a file from a torrent;
- `{"root": N, "path": "folder/video.mkv"}`: a file from the library;
- `{"playing": true}`: whatever is playing in mpv right now.

`App.resolve_ref` handles this and always returns a path inside the download folder or a library folder.

### Library folder visibility

Besides `name` and `path`, a folder in `config.json` (`library`) has a stable `id`, `tags`, `visible` (`"all"`,
`"owners"` or a list of device ids) and `exclude` (subfolders relative to the folder). After checking the token, the
handler stores in the thread who is asking (`mp_library.set_viewer`), and `Library.root`/`resolve`/`browse` refuse a
hidden folder and an excluded path. Without a viewer set (single-file ticket, background threads) there are no
restrictions: a ticket is issued only after the check. Folder indexes stay global, so a guest's list can have gaps
(e.g. 0 and 2).
Plan for the next steps (magnet → folder by tag, tidying up, covers): `plan-library.md`.

## Flows

### Magnet → download
1. `MagnetActivity` (transparent) receives `magnet:` from a link or from "Share".
2. `Api.ensureAwake`: first `GET /api/ping`. When the computer doesn't respond, the app sends a magic packet
   (`255.255.255.255` and the subnet broadcast, ports 9 and 7) and waits for it to come up.
3. `POST /api/torrents` with `magnet`, `sequential` and `after`. The agent adds the torrent to Deluge with
   `sequential_download` and `prioritize_first_last_pieces` and records the job in `jobs.json`.
4. The app shows the "When downloaded" chips and closes itself after a moment.

### How much can be watched
From `core.get_torrent_status` the agent takes `pieces`, `piece_length` and `files[].offset`. `contiguous_ready`
counts the bytes downloaded contiguously from the start of the file. mpv provides the video duration
(`--term-playing-msg=${=duration}`). This is computed in the background, at most 2 at a time, and the result is
cached. These give `ready_sec`, i.e. how many seconds can be watched.

### Watching
- **Phone:** ExoPlayer streams `/media/<torrent>/<file>` or `/lib/<folder>/<path>` (Range). ExoPlayer keeps the
  default ~50 s in memory. A further buffer lives on the phone's storage (`MediaCache.kt`, `SimpleCache` in
  cacheDir): `PlayerActivity.prefetch` fetches N minutes ahead of the current position (Settings, 1–999, default 5;
  999 = the whole video for the road), but only up to `X-Ready-End` from the agent, so only downloaded bytes. The
  player only reads from the buffer, it doesn't write.
  - Cleanup: the end of the video or leaving the player after ≥80% deletes the file right away. At app and player
    start, `MediaCache.sweep` removes videos watched ≥80% (e.g. when the app was killed) and those not watched for
    48 h. LRU limit: 60% of free space, 1–64 GB (`MediaCache.maxBytes`).
    Locking the screen deletes nothing.
  - The on-disk buffer shows on the seek bar as a blue segment (`DiskAheadOverlay`, an overlay on `DefaultTimeBar`);
    the grey one from ExoPlayer is the in-memory buffer.
  - For torrents the agent serves only downloaded pieces (`ready_end`). When it reaches a missing one, it waits up
    to 20 s for Deluge, then drops the connection, and ExoPlayer retries (up to 60 times). Nobody gets zeros from
    an undownloaded file.
  - Subtitles next to the video are added as `SubtitleConfiguration`. The app downloads them from `/sub` to the
    phone (cacheDir/subs) when opening the video, because media3 re-reads the subtitle file when seeking and
    switching tracks. Over the network, without Wi-Fi, that would block fetching the video from the on-disk buffer
    and the picture would stutter.
  - The FFmpeg decoder (Jellyfin media3-ffmpeg) handles AC3, E-AC3 and DTS audio.
  - "To computer" passes the current position.
- **On phone (offline):** "Download to phone" in the video's ⋮ menu → `DownloadService` (foreground service
  `dataSync`, Wi-Fi and CPU locks) downloads the whole file into the same `MediaCache`, only up to `X-Ready-End`
  (for a torrent still downloading it waits for the computer; after ~5 min without a connection, "Stopped").
  Subtitles are saved to `filesDir/offline/<sha1>/`.
  - The list in the "On phone" tab (`OfflineTab.kt`, `OfflineStore` in `Offline.kt`): downloads and the buffer from
    watching, offline playback, resuming, deleting.
  - Downloads (`pinned`) are deleted according to the rule chosen for the video: after watching (default), 2/7/30
    days after download or after the rule changed, or manually only. `PinnedAwareLruEvictor` doesn't evict them
    when space runs out.
  - The file length goes into the cache metadata (`MediaCache.setLength`), so the player knows where the file ends
    when offline.
  - The web version doesn't have this: it plays straight from the PC; watching away from home is a separate topic
    (Tailscale/WireGuard).
- **VLC:** the same URL plus the `subtitles_location` extra.
- **Computer:** `POST /api/player/open` starts mpv in the graphical session. The agent takes the environment
  (`DISPLAY`, `WAYLAND_DISPLAY`, DBus) from `/proc/<cinnamon-session>/environ`. The Remote controls mpv via JSON IPC.
  mpv starts via `systemd-run --user --scope`, so restarting or updating the agent doesn't close the video. A new
  agent recognizes a running mpv by its IPC socket. The agent adds subtitles from a subfolder (`Subs/…`) itself
  (`sub-add`), because mpv only looks next to the video. The phone supplies the start position from its own memory,
  because the video may have last been watched elsewhere.
- **Lock screen:** before playback the agent wakes the monitor (`xset dpms force on`) and, when `auto_unlock` is on
  (default), calls `loginctl unlock-session <Display session>`. Logind sends the Unlock signal, on which
  cinnamon-screensaver (and GNOME, KDE) removes the lock. Polkit allows this for the session owner without a
  password. We don't use `cinnamon-screensaver-command --deactivate`, because in Cinnamon 5.4 it crashes the
  screensaver and hangs.

### Subtitles
`search` calls `scan_video` and `refine` (hash and metadata), then queries each provider in a separate thread
(`list_subtitles_provider`) and records progress, which the app reads from `/api/subs/job`.
- **Wrong-title filter:** results without a matching title (or series) and without a matching hash are hidden. The
  app shows only their count. Without this, OpenSubtitles returns "similar" movies when nothing matches, e.g. for
  Der Golem it suggested a completely different film.
- **Second round:** when nothing matches and the file name has a title in `[brackets]` (e.g.
  `Der Golem [The Golem]`), the agent searches again with that title. Providers that search by hash only (BSPlayer,
  NapiProjekt) are skipped then.
- **Manual correction:** the app can supply `title` and `year`.
- OpenSubtitles is limited to 3 pages of results.

Results are sorted by the language order from the settings, then by score, and the objects are cached for an hour.
`download` fetches the chosen subtitles and saves them next to the video. If that video is playing in mpv, it adds
them right away with `sub-add`.

OpenSubtitles without a login: subliminal requires logging in (`@requires_auth`) before downloading, while the API
allows downloading with just a key. That's why `_oscom_anonymous` calls `download_subtitle.__wrapped__`.

### Background file tasks (agent 1.6+)
`mp_tasks.py`, the API in [api.md](api.md#disks-background-tasks-storage-profiles-agent-16-featureslibrary_tasks).
1. A planner proposes items in a background thread and changes nothing; the phone (or an MCP assistant) shows the
   preview with checkboxes.
2. The chosen items become one task in `tasks.json`; one worker runs tasks one after another, an interrupted task
   continues after a restart (a half-done item is redone).
3. Moving: a torrent still in the client → `move_storage`/`torrent-set-location` and waiting until the client
   reports the new folder (it keeps seeding); a folder containing torrents is skipped; the same filesystem →
   rename; another disk → copy to `*.mp-part`, compare sizes and file counts, rename, delete the source. Subtitles,
   `.nfo` and pictures next to a single video go with it; `.mp-cast` versions are dropped (made again on demand).
4. Shrinking (storage profiles, decision 16 in plan-library.md): `original` (default, always for `kopia`),
   `tidy` (lossless: the picture is copied, audio tracks other than Polish/English/undefined/default dropped,
   DTS/TrueHD/PCM → E-AC3 640k or AAC; at least 3% saved), `good` (HEVC ≤1080p, x265 CRF 24), `compact` (≤720p, CRF 25, stereo), `tiny` (≤480p, CRF 26, small stereo AAC);
   smaller profiles keep Polish, English, undefined and default audio tracks; subtitles and chapters are copied.
   Only files outside the torrent client; HEVC/AV1 already small enough and estimates saving under 15% are
   skipped. x265 `-preset fast` at `nice 19` and `ionice -c3` (`shrink_encoder: "gpu"` = NVENC), SIGSTOP while a
   stream is active. The result must have the same duration (±0.5%), decode without errors and be at least 7.5%
   smaller; then the original goes to `<library folder>/.mp-trash/<date>/` and the `.mkv` result takes its place.
   The trash is purged after `trash_days` (default 14).
5. The phone shows one notification per finished task.
6. Replication (decision 5): a folder with `replicate_to` (another folder's id, set in the folder's settings) gets
   its new or changed top-level items copied there every 30 minutes and after each task, quietly: no notification,
   the state (`pending`, `copied`, `last`, `error`) is in `GET /api/library` as `replica`. Never deletions (a copy,
   not a mirror), never an unfinished torrent, and a round stops when somebody starts streaming or a task is queued.

### Covers
`mp_media.cover()`: a picture next to the video or in the folder first (Jellyfin/Kodi names), otherwise one frame
from ~10% into the video (the biggest video of a folder, samples skipped) via ffmpeg, cached by path, size and mtime.
No network or accounts; TMDB posters remain an option for later.

### MCP for an AI assistant
`mp_mcp.py`: the assistant (e.g. Claude Code on a computer in the LAN) is added with
`claude mcp add --transport http magneticplayer http://<server>:8765/mcp`. The first call gets `401`, the client
registers itself, opens `/oauth/authorize` in the browser, and that page files an access request: the phone shows
"<client> (AI assistant) is requesting access". "Member" = all tools, "Guest (1 day)" = read tools for a day. The
page then pairs, the client exchanges the code (PKCE) for an ordinary device token, which the phone can revoke.
Changing tools only make plans; `start_task` runs one after the user has seen it.

### Uploading downloaded videos from the phone
A setting, off by default (details and decisions: [plan-upload.md](plan-upload.md)).
1. The browser finishes downloading to `Download/`. A WorkManager MediaStore trigger (also when the app is closed)
   or a ContentObserver notices the new video (`IS_PENDING = 0`, added after the setting was turned on, not in
   `DCIM/`).
2. A notification right away: "Send" or "Send and delete from phone".
3. `UploadWorker` (Wi-Fi only): when the server doesn't answer `ping`, it waits and retries every 15 min. Then
   `GET /api/upload/<id>` and `PUT` in 64 MB chunks from `received`.
4. The agent (`mp_upload.py`) assembles the file in `.mp-upload/<id>.part` and moves it to `Z telefonu` in the
   library.
5. With deletion: "Tap to delete from phone" → the system confirmation `MediaStore.createDeleteRequest`
   (Android 11+).

### Sleep
- Every 20 s `SleepGuard` checks whether something is downloading or playing. If so, it holds
  `systemd-inhibit --what=sleep --mode=block`. It also calls `jobs.tick`.
- With the `stop` action, `jobs.tick` pauses the torrent right after it finishes downloading.
- With the `suspend` action it waits 60 s after finishing and suspends the computer only when nothing is
  downloading or playing. Each job is executed once.
- The "Sleep" button closes mpv and calls `systemctl suspend`. Polkit allows this for the background service.

### App updates
The agent serves the app to phones from `~/.local/share/magneticplayer/apk/` (`MagneticPlayer.apk` and
`version.json`). It gets there in two ways, always the APK first, then the version:
- **from GitHub releases** (`mp_appupdate.py`, mode `app_updates: "auto"`, the default): one minute after the agent
  starts and then every 6 h the agent reads `releases/latest/download/version.json`. When `version_code` is higher
  than the one being served, it downloads the APK and checks the certificate fingerprint in the APK v2/v3 signing
  block (treetank `05f72b11…`). Android verifies the signature at install time. It never downgrades. The phone
  switches the mode in the server details ("App updates from GitHub");
- **manually** with `publish.sh` (the only way in `manual` mode).
The app compares versions and downloads a newer one straight into a `PackageInstaller` session. `UpdateReceiver`
launches the system confirmation dialog.

### Agent updates
The agent updates itself from the same releases (`mp_selfupdate.py`, mode `agent_updates`, agent 1.6+, Linux): a
signed manifest (ECDSA P-256, verified in the agent), only a higher `agent_version`, installation at a quiet moment by
`self-update.sh` in its own systemd unit with a rollback when the new agent doesn't answer `/api/ping`. Details in
[install-and-maintenance.md](install-and-maintenance.md#agent-self-update-agent-16-linux). The phone only switches the
mode and can say "Update now"; it never sends code to the agent.

## Web client (`server/web/index.html`)

A single file without dependencies (vanilla JS), served at `/` without a token. The data goes through the API with
a token anyway.
- The token arrives in `#t=`, goes to `localStorage` and disappears from the address bar. `#tab=` selects a tab.
- Tabs: Downloading (torrents), Library and Remote (polling `/api/player/status`, tracks, keyboard shortcuts).
- The page reads `/api/info` once and hides what the server doesn't have: "Sleep" without `power.suspend`, "Screen"
  without `features.player`/`unlock`, Torrents without `features.torrent`, and without `features.player` also
  "▶ On computer" and the mpv remote (the Remote then shows only Chromecast). The header status shows the server's
  `name`.
- Language: `navigator.language` (`pl*` → Polish, otherwise English), the dictionary `T = {en, pl}` and
  `tr(key, …)`; static text in the HTML via `data-i18n`, `data-i18n-html`, `data-i18n-title`,
  `data-i18n-placeholder`. `<html lang>` and the `Accept-Language` header in API requests are set to the same
  language.
- The subtitle dialog works as in the app (asynchronous job, progress, "That's enough").
- The player is a `<video>` with WebVTT subtitles (`/sub?…&vtt=1`).
- The DOM is built with the helper `put(el, ...children)`, which flattens arrays and skips `null`. A bare
  `replaceChildren` with an array printed "null[object HTMLDivElement]".

## App (`phone/`)

Strings are in resources: English by default (`res/values/strings_*.xml`), Polish in `res/values-pl/`. The app
language follows the phone's language; on Android 13+ it can be changed separately in system Settings → "App
languages" (`res/xml/locales_config.xml`). Requests to the agent carry this language in `Accept-Language`, so
agent errors come back in the same language.

| file | contents |
|---|---|
| `Api.kt` | `Settings` (SharedPreferences), models, `MediaRef` (with `srv`), `Wol`, the HTTP client for one server, waking (also through another server) |
| `Servers.kt` | server list, selected file server and screen, migration of old settings (the `legacy` server), pairing (`Pairing`) |
| `ServersUi.kt` | server switcher, "asleep" panel, sleep dialog, screen picker, Settings → Servers, adding, devices, "Pair another" |
| `MainActivity.kt` | Downloading (torrents), Remote and Settings tabs, top bar (server, Wake/Sleep), update bar |
| `Announce.kt` | broadcasting "what's playing" (multicast, `MulticastLock`): the phone announces the DLNA TV it started a video on and listens to others (the Remote takes over someone else's TV via `Dlna.adopt`) |
| `CastNet.kt` | Chromecasts from the phone without the agent and without the Cast SDK: mDNS discovery (NsdManager, while the Remote is on screen) and its own Cast v2 protocol client (TLS :8009, hand-written protobuf): state from any sender, pause, seek, volume, close |
| `Remote.kt` | the shared "remote player" (mpv, Chromecast, DLNA) and the Remote's target choice (`Pilot`) |
| `RemoteRoutes.kt` | a MediaRouter2 route provider: the name of the playing target (e.g. "jmpc", a TV) on the system media card instead of "This phone" |
| `RemoteSession.kt` | media3 sessions for every playing target (mpv, DLNA, Chromecast without the SDK): notifications, lock screen, volume buttons outside the app |
| `Cast.kt`, `Dlna.kt` | Chromecast (Cast SDK, MediaRouter) and DLNA TVs (SSDP, SOAP) |
| `PhoneShare.kt` | the phone as a file server for a screen, Chromecast and DLNA |
| `LibraryTab.kt` | the library (folders of every server, tag filter, search, covers), folder settings (tags, visibility, storage profile, "Also copy to") and picking a folder on the computer |
| `LibraryTasks.kt` | agent 1.6+: "Disks and tidy-up" (disks with the library vs other files, trash, tasks with progress and cancel), the plan preview with checkboxes (free up space, move, tidy, shrink, duplicates, delete), profile choice, covers (`Covers`, `CoverThumb`) |
| `Tasks.kt` | one notification per finished file task (checked with `Access` while the app is on screen) |
| `MediaUi.kt` | Phone, screen and VLC buttons, subtitle dialog |
| `PlayerActivity.kt` | the player on the phone |
| `MagnetActivity.kt` | handling magnets |
| `Updater.kt` | updates from the computer, sharing its own APK |
| `Access.kt` | access requests: checking on connect (loop in `MainActivity`), a notification with Guest/Member/Decline buttons, `AccessReceiver` |
| `InstallShare.kt` | the phone as the installer source: an HTTP server for the duration of the install dialog with the agent bundled in the APK (same routes as `mp_install.py`) |
| `AgentBundle.kt` | the agent bundle for `InstallShare`: from the APK or a newer one from a GitHub release (manifest signed with ECDSA P-256, sha256 of the bundle, minimal tar reader) |
| `InstallUi.kt` | "Install on another computer": the command with a join code, waiting for the new server and pairing with it |
| `UploadOverlay.kt` | the "Send to server?" pop-up over the browser ("Display over other apps" permission) |
| `Uploads.kt` | uploading videos downloaded by the browser to a server: detection (WorkManager with a MediaStore trigger, ContentObserver), the question in a notification, `UploadWorker` (waits for the server's Wi-Fi, resumes), `UploadDeleteActivity`, settings |

Keys for positions, the buffer and downloaded videos are prefixed with the server (`<key>:`), except for the
`legacy` server (migration from a single server), so old data is kept.

The code is Kotlin 2.2 with Compose (BOM 2025.08) and Media3 1.8. It requires Android 8 (minSdk 26) and targets
SDK 36.
