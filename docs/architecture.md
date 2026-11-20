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
| `magneticplayer_agent.py` | entry point: `App` (wires the modules together, status and `features`), the HTTP `Handler` (authorization, guest permissions `GUEST_ALLOWED`, error handling, Range streaming helpers, dispatch through the route table), the CLI (`--pair`, `--join`), `VERSION` |
| `mp_core.py` | shared basics with no agent dependencies: config load/save, `log`, `ApiError`, `WEB_DIR`/`APK_DIR`, `apk_version`, `lan_ip` |
| `mp_routes.py` | the route table: `(method, pattern, handler, options)` rows with typed `<name:type>` parameters, public rows (no token) first; `call("x.y", "param")` points a row straight at an `App` method and is resolved at startup, so a route pointing at nothing stops the agent from starting |
| `mp_api_*.py` | route handlers by topic, `fn(req, app, *, method, p, q, qd, **params)`: `system` (ping, info, power, agent update), `access` (pairing, access requests, install scripts, OAuth/MCP), `media` (streams, tickets, variants), `player`, `torrents`, `library`, `tasks`, `subs`, `catalog`, `config` |
| `mp_torrents.py` | Deluge and Transmission backends (and `NoBackend`), computing "how much can be watched", video durations |
| `mp_mpv.py` | mpv control over its IPC socket / named pipe, choosing the audio track |
| `mp_power.py` | `SleepGuard` (keeps the computer awake while something plays or downloads), poweroff and suspend, the `POWER`/`AUTO_UNLOCK` settings set by `App` |
| `mp_jobs.py` | history of jobs sent from the phone and the after-download action (`none` / `stop` / `suspend`) |
| `mp_library.py` | library folders, listing only video and audio files, subtitles next to videos, protection against escaping the folder (`safe_join`) |
| `mp_subs.py` | searching and downloading subtitles via subliminal (lazy import from the venv) |
| `mp_devices.py` | paired devices (tokens stored as SHA-256), pairing codes, HMAC tickets for a single file; `devices.json` is re-read when its mtime changes (the CLI is a separate process) |
| `mp_cast.py` | Chromecast via pychromecast: discovery, listening to the state of all devices (any sender), playback and control for the web page and the Remote |
| `mp_announce.py` | broadcasting "what's playing" between remotes (UDP multicast `239.255.77.77:47700`): listening for the web page (`/api/announce`), announcing this server's mpv, answering "who" |
| `mp_renderers.py` | who the Chromecasts are (`product_name` from `eureka_info`), codec profiles per model (`CAST_PROFILES`) and the `renderers.json` registry; described in `docs/devices.md` |
| `mp_access.py` | access requests from the home network: a request without a token, the list and a household member's decision, handing a pairing code to the requester |
| `mp_install.py` | installing the agent on another computer: join code, the `/i/<code>` script (templates `join.sh`, `join.ps1`, shared with the phone), the bundle, registering the new server with a pairing code for the phone |
| `mp_upload.py` | receiving videos from the phone (`/api/upload/<id>`): resumable chunks in `.mp-upload/`, free space, name collisions, the `upload_dir` folder or `Z telefonu` ("From phone") in the library; history of received files (`uploads.json`) with keep / move to a library folder |
| `mp_catalog.py` | the library as films (agent 1.8+): `Catalog` groups library files into titles, recognises them (`mp_ident`), reads audio and subtitle languages, adds Wikidata details by IMDb id (cast, directors, topics, age) and answers title, person and "what next"; `Wishlist` is the shared want-to-watch list |
| `mp_selfupdate.py` | the agent updates itself from signed GitHub releases (ECDSA P-256 verified in pure Python), at a quiet moment, through `self-update.sh` with a rollback |
| `mp_tasks.py` | background file tasks with a preview: planners (free up a disk, tidy up by recognition, duplicates, shrinking by storage profiles, deleting to the trash; 1.16+: sound fix, modernize, downscale, compress, task counters from a background scan; 1.17+: folder layout, balance between disks, For Chromecast, pause, resume and queue order) and one worker; moving through the torrent client when the torrent is still in it; `.mp-trash/` per library folder |
| `mp_tasks_*.py` | mixins of `Tasks`, split by topic: `common` (constants, helpers, `TaskError`), `plan` (v1 planners), `jobs` (v2 planners, task counters), `layout` (folder layout, balance, For Chromecast), `facts` (background scan, played files, audio), `ops` (subtitles, replicate, move, trash), `shrink` (encoders and shrinking) |
| `mp_scope.py` | task scopes (agent 1.16+): parsing a scope, deciding which files it covers, its label |
| `mp_arrange.py` | folder layout and balance between disks (agent 1.17+): whole-title units, hand-arranged folders, destinations by levels, moves for a balance goal |
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
  - A picture the phone has no decoder for (old DivX 3 / `msmpeg4v3`, WMV…): ExoPlayer would play the sound alone
    and could hang ("stuck buffering"). The player notices it in `onTracksChanged`, or on any non-network playback
    error (media3's AVI extractor passes DivX 3 off as MPEG-4, so the track looks supported), and switches to the
    agent's "for Chromecast" version (H.264 MP4, `POST /api/media/convert`, ticket with `variant: "cast"`) when
    there is one; otherwise it asks: Prepare (progress in the top line, then the switch at the same position),
    VLC, or sound only. The prepared version isn't put in the disk buffer.
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
them right away with `sub-add`. An SRT where nearly every cue lasts zero time or has no text (such uploads exist on
OpenSubtitles, even with a hash match) is refused with 422 instead of being saved. Sidecar subtitles match the video
name case-insensitively (`Le film.srt` next to `Le Film.avi`), and so do the companions moved with a video.
**Versions (agent 1.12+, `mp_subfiles`):** the default version of a language is the one used last, as
`<name>.<lang>.srt` next to the video (what mpv, VLC, TVs, Jellyfin and Plex find by themselves). Other versions wait in
`Subtitles/<name>.<source>.<lang>.srt` (source = provider and release, telling them apart; the video's name in front
ties them to one episode of a season folder, so other episodes don't list them). Downloading moves the current
default there instead of overwriting it; `POST /api/subs/default` swaps a version back. Where each file came from
(provider, id, release) is kept in `subs-sources.json` (the app marks search results the user already has), and the
fitting assessments move with the files. A seeding torrent's file is never moved: a new download then goes to
`Subtitles/`, and picking a torrent's own file copies it. Torrent layouts (`Subs/English.srt`, `Subs/<name>/2_English.srt`)
are still listed as versions.
`/sub` serves every `.srt` as UTF-8 SubRip (`mp_subs.readable_srt`): many files named `.srt` are really MicroDVD
(`{frame}{frame}text`, frame rate from the `{1}{1}25.000` line or the video), MPL2 or TMPlayer, often in cp1250. mpv
reads those itself, but the phone and browsers showed nothing. The file on disk is not changed. A text `.sub`
(MicroDVD/MPL2/TMPlayer, not VobSub) is listed as `ext: srt` and served the same way.

**Fitting to the film (agent 1.9+, `mp_subsync.py`, `features.subs_fit`).** Subtitles found online are often made for
another release: shifted (another intro) or timed for another frame rate (23.976 vs 25 fps: seconds off at the start,
minutes by the end), even with a hash match. The agent detects speech in the video's sound (ffmpeg → 16 kHz mono →
webrtcvad, 10 ms frames, cached per video in `~/.cache/magneticplayer/speech/`, ~20–30 s for a film), turns the cues
into the same kind of signal for each usual frame-rate ratio, and cross-correlates them (FFT, lags up to ±5 min). `z`
says how far the best match stands above all other lags: subtitles of the film come out at 7–9, subtitles of another
film at 2–3.5 (measured on the library), so `z` ≥ 6 = fits (shifted/rescaled in place when it changes ≥ 0.4 s or the
ratio), < 4.5 = doesn't fit this copy, in between = unsure (left alone). A text `.sub` becomes `.srt`. Originals go to
`<config>/subs-originals/<date>/`, assessments are kept per file (path, size, mtime) in `subsync.json` and shown in
`/api/subs/list` as `fit`. Files that belong to a torrent in the client are only assessed, never changed (they are
being seeded). It runs after every download (the app waits with `"fit": "wait"`, older clients get it in the
background), on `POST /api/subs/fit`, and for the whole library as the "Subtitles" task (`kind: subs`). The player
labels tracks (✓ / "don't fit") and picks the best-fitting file of each language as the default.

Agent 1.12.1+: when the video carries its own subtitle track (text, PGS or VobSub, the one with the most lines), its
display times are the reference instead of the speech (ffprobe packets, ~7 s for an episode, cached next to the
speech). They are exact to the frame, while the speech of an action film is buried under music: on one episode the same
correction (×1.001, −3 s) came out at z 5.7 against the speech and 9.8 against the embedded track. When the embedded
track doesn't give "fits" (another cut, or other subtitles), the speech decides. By hand: `POST /api/subs/shift` moves
the file (the "Timing" buttons on a language's page of the subtitle sheet: ±0.1 and ±0.5 s), saved in the file so mpv
and TVs get it too; the player reloads it.

OpenSubtitles without a login: subliminal requires logging in (`@requires_auth`) before downloading, while the API
allows downloading with just a key. That's why `_oscom_anonymous` calls `download_subtitle.__wrapped__`.

### Background file tasks (agent 1.6+)
`mp_tasks.py`, the API in [api.md](api.md#disks-background-tasks-storage-profiles-agent-16-featureslibrary_tasks).
1. A planner proposes items in a background thread and changes nothing; the phone (or an MCP assistant) shows the
   preview with checkboxes.
2. The chosen items become one task in `tasks.json`; one worker runs tasks one after another, an interrupted task
   continues after a restart (a half-done copy is redone). Encodes (shrinking and "for Chromecast" versions, agent
   1.9+) run detached from the agent (`mp_detached.py`): a small keeper process outside the agent's process tree (its
   own systemd scope on Linux, `DETACHED_PROCESS` + `CREATE_BREAKAWAY_FROM_JOB` on Windows) runs ffmpeg, holds a lock on
   `<config>/encodes/<id>/lock` while it runs and writes the progress, the pid and the exit code there. After a
   restart or a self-update the agent finds the encode by the id in the task item and attaches to it again; one whose
   keeper died without an exit code (a reboot) is started over, and encodes no task owns are stopped. That is why a
   self-update no longer waits for an encode, only for copies, moves, replication and queued conversions.
3. Moving: a torrent still in the client → `move_storage`/`torrent-set-location` and waiting until the client
   reports the new folder (it keeps seeding); a folder containing torrents is skipped; the same filesystem →
   rename; another disk → copy to `*.mp-part`, compare sizes and file counts, rename, delete the source. Subtitles,
   `.nfo` and pictures next to a single video go with it; `.mp-cast` versions are dropped (made again on demand).
4. Shrinking (storage profiles, decision 16 in plan-library.md): `original` (default, always for `kopia`),
   `tidy` (lossless: the picture is copied, audio tracks other than Polish/English/undefined/default dropped,
   DTS/TrueHD/PCM → E-AC3 640k or AAC; at least 3% saved), `good` (HEVC ≤1080p, x265 CRF 24), `compact` (≤720p, CRF 25, stereo), `tiny` (≤480p, CRF 26, small stereo AAC);
   smaller profiles keep Polish, English, undefined and default audio tracks; subtitles and chapters are copied. Audio the phone cannot play (WMA, Cook…: not in `PLAYABLE_AUDIO`) is re-encoded by every profile (1.11+).
   Only files outside the torrent client; HEVC/AV1 already small enough and estimates saving under 15% are
   skipped. Old picture formats that phones, TVs and Chromecasts no longer decode (`OLD_VIDEO`: DivX 3, WMV,
   RealVideo, Flash, Theora, Cinepak, Indeo, MPEG-1, since 1.10.1 MPEG-4 Part 2 = XviD/DivX 4+, whose B-frames phones get wrong from AVI, and since 1.11 H.264 with 10 bits or 4:2:2/4:4:4 = anime "Hi10P", which no phone decodes: `mp_tasks.is_old`) are the exception: they are re-encoded even when that saves
   little (the result may be up to 25% bigger), and `tidy` encodes them as `good`, since a copied old picture stays
   unplayable. "Old formats" (`kind: modernize`) finds them in every library folder except backups, whatever the
   folder's profile. Since 1.15 it also lists videos with a modern picture whose sound has damaged frames: MP3/MP2/AC3
   tracks (`AUDIO_SUSPECT`) that ffmpeg and VLC play by skipping frames ("Header missing") but that stop a phone's
   decoder. Finding them takes a full decode of the track, so a background thread (`_audio_scan`, 5 min after start, MP3 and small files first,
   then daily, `nice`/`ionice -c3`, waiting while somebody watches or a task runs) decodes each such file once and
   remembers the result in `audio-checks.json` (by path, size and mtime); a preview checks files the scan hasn't reached
   for 90 s at most and says how many it left. Such an item (`audio_fix: true`) copies the picture and re-encodes only
   the damaged tracks to AAC (`aresample=async=1` keeps the sync), and the result's sound must decode cleanly. Any
   shrink also re-encodes a damaged track it would otherwise copy (1.14.5+). Odd frame sizes are rounded to even ones. The encoder is chosen per task (agent 1.7+): `cpu` = x265 `-preset fast` at `nice 19` and `ionice -c3`,
   `gpu` = the card's HEVC encoder (NVENC or VAAPI) found by `mp_media.detect_hevc` with trial encodes: the full
   set of options first (B-frames, AQ, lookahead), then fewer, so an old card (GTX 970: no HEVC B-frames) still works
   and is marked as making larger files. `auto` (the default `shrink_encoder`) takes the card only when it can do
   B-frames. The trial also gives a first speed (two encodes of 2 and 8 s of 1080p, the difference leaves out
   ffmpeg's start); real encodes refine it in `shrink_speed`, and the preview shows saving and hours of work for
   each encoder. While somebody watches (`shrink_while_watching`: `continue` by default, `pause` = stop on any
   stream) a governor thread next to ffmpeg decides: another disk → full speed; the same disk → one second in three
   (SIGSTOP/SIGCONT) only when the encoder itself reads over 8 MB/s (`rchar`; x265 reads ~0.3 MB/s, NVENC tens of
   MB/s; measured on jmpc: a 3 MB/s stream from the same USB disk kept its pace, and an unthrottled one got 43 MB/s
   next to x265); a conversion for a TV → stop. "Watching" = a GET of that file in the last 30 s, mpv playing it, or
   a conversion; HEAD-only requests (a phone playing its own copy probes the file every few seconds) don't slow
   anything down but do hold the swap: the result replaces a file only when nobody has touched it for a minute,
   since a player asks for byte ranges of the original. The result must have the same duration (±0.5%), decode without errors and be at least 7.5%
   smaller; then the original goes to `<library folder>/.mp-trash/<date>/` and the `.mkv` result takes its place.
   The trash is purged after `trash_days` (default 14). Before encoding, the item needs room for the result next
   to the original (its estimate + 256 MB): the trash is on the same disk, so a big run frees nothing for 14 days.
   Agent 1.14.2+ guards a disk going full (a run once filled one: hundreds of "no space left"): a disk under its
   reserve (5 %) sends the replaced original to the trash of a library folder on another disk with room
   (`<other folder>/.mp-trash/<date>/<source folder name>/…`), and before an encode that would leave the disk under
   the reserve, its older trash days move to such a disk first. If the disk would still go under the reserve and no
   other disk has room for the original, the item isn't started (its error says so). Nothing is deleted early; the purge after
   `trash_days` covers every disk's trash.
5. Only the phone that started a task follows it (another one can pick "Monitor" on the task card): an ongoing
   progress notification, like a download (`TaskWatchService`, read once a minute in the background, every 15 s while
   the app is on screen), then one "finished" notification. Other phones don't hear about it.
6. Replication (decision 5): a folder with `replicate_to` (another folder's id, set in the folder's settings) gets
   its new or changed top-level items copied there every 30 minutes and after each task, quietly: no notification,
   the state (`pending`, `copied`, `last`, `error`) is in `GET /api/library` as `replica`. Never deletions (a copy,
   not a mirror), never an unfinished torrent, and a round stops when somebody starts streaming or a task is queued.
7. Subtitles (`kind: subs`, agent 1.9+): every video with SRT or text SUB files next to it, in every library folder
   except backups, files assessed before left out; each item fits its files (see Subtitles). It waits while somebody
   watches from the same disk, since the whole file is read for its sound.
8. Task boards (agent 1.16+, `features.jobs_v2`, [api.md](api.md#task-scopes-counters-and-new-task-kinds-agent-116-featuresjobs_v2)):
   every plan and the counters of `GET /api/library/jobs` take a **scope** (`mp_scope.py`: folders, single files, a
   catalog title, a tag, age groups), parsed once into a `Matcher` that snapshots the catalog; old planners' results
   are filtered by it afterwards, new ones apply it while walking. The counters never probe inside a request: a
   background **facts scan** (`job-facts.json`; 60 s after start, then every 30 minutes and soon after a library
   change) keeps per video its probe, sidecar subtitles (re-listed when the folder changes), inode, real path and
   sampled hashes for duplicates; files of unfinished torrents count as `pending`. Counters and plans share one
   selector (`Tasks._pick`), so a board's number is the size of the plan it opens. New kinds reuse the `shrink` item
   with per-item overrides: `audio_fix` (damaged tracks only, AAC 128k/256k or 192k/384k), `modernize` with `audio`
   (H.264 at the same resolution: the card's H.264 encoder or x264 `-preset medium`), `downscale` (H.265 to
   1080/720/480/360, profiles `good`/`compact`/`tiny`/`mini`) and `compress` (H.265 at the same resolution, profile
   `same`, three qualities). `dupes` with `what` also finds one catalog title in several copies and marks which copy
   stays; `free` with `amount`/`to_percent`/`order` deletes whole titles to the trash instead of moving them.
   "Watched" is `played.json`: the last time each video was streamed from this server (a GET, not a HEAD); a title
   streamed in the last 14 days is "being watched" (kept when freeing space, `subs` `which: watched_now`). With
   `audio_fix_auto` / `subs_auto` (`/api/config/jobs`) new files found by the scan get a task by themselves. A task
   stores the scope's label in both languages and returns it as `scope_label`.
9. Folder layout, balance and For Chromecast (agent 1.17+, `mp_arrange.py`,
   [api.md](api.md#folder-layout-balance-for-chromecast-pause-and-queue-order-agent-117)). Both arrangers work on
   **units**, whole titles from the catalog (a film's file in a folder of its own counts as that folder, so subtitles and
   covers move with it), marked with their torrent and whether another queued or running task still uses their files
   (`busy`, skipped). `layout` turns the chosen levels (kind, age group via an editable age → Kids/Teens/Films mapping,
   category, genre, film series, decade) into a destination on the same disk: the library folder whose tag fits the
   kind, then subfolders; folders the owner arranged by hand (two or more titles under a name that isn't the layout's)
   are detected and stay as they are once confirmed (`layout_keep`). Several folders with the kind's tag on one disk
   are picked like CSS specificity (1.17.1+): a folder fits only when the title has every one of its tags (`bajki` +
   `pl` = a cartoon with Polish sound), and the fit with the most tags wins. Title tags come from the kind, the age
   group, the category, sound languages from the facts scan (a track without a language makes them unknown) and tags
   set by hand (`title_tags`, MCP `set_title_tags`); an unknown language or a tie keeps the title where it is. The
   Kids group is "for children", not "low age rating": a children's film or intended public children in Wikidata, or
   animation without dark genres (`for_kids()`); titles already in a kids folder stay there. Its moves are same-disk renames, so the task is
   **quick**: it runs between two files of a running encode instead of waiting behind it, and removes the folders it
   empties. The catalog looks into the layout's own folders (`Films/`, `Kids/`, a decade) and into decade folders, so
   a title keeps being a title after it moved there. `balance` moves whole titles between disks into the folder with
   the same tag on the other disk (same subfolder, so the layout survives), for one of three goals, never below 5%
   free; seeding torrents move whole through the client (decision 13), titles watched lately stay. `cast_ready` writes
   text subtitle tracks as `.srt` and remuxes to `.mkv` with an AAC stereo track first, picture copied, original to
   the trash; the facts scan (`FACTS_V` 2) now keeps subtitle codecs, so its counter can tell text from picture
   subtitles.
10. Pause, resume and queue order (agent 1.17+, `features.task_queue`): a task paused by a person is `held` (persisted
   in `tasks.json`). The worker skips held queued tasks; a running one stops at the next file boundary, or its
   detached encode is stopped with SIGSTOP and the task goes back to `queued` keeping the encode's job, so resuming
   (even after an agent restart) re-attaches to the same ffmpeg and continues; an encode left stopped is killed when
   its file changed or vanished. Tasks can be moved up or down the queue or re-ordered as a whole; a finished task
   whose files failed for lack of space can be retried as a new task after emptying the trash.

### Covers
`mp_media.cover()`: a picture next to the video or in the folder first (Jellyfin/Kodi names), otherwise a frame of
the video (the biggest video of a folder, extras and samples skipped) via ffmpeg, cached by path, size, mtime and
width (360 px, 720 for full-width pictures). The frame (agent 1.10+): a fifth into the video, past the opening
credits; `cropdetect` on 3 s finds the black bars above and below (threshold as a fraction, so 10-bit video works) and
they are cut off, since a portrait card would show them; `thumbnail` picks the most typical of ~3 s of frames, not a
black or half-faded one. The answer says `X-Cover-Kind: frame` or `picture`: the app puts the title over a frame
(darkened at the bottom) and shows a poster as it is. No network or accounts; TMDB posters remain an option for later.

### Catalog (agent 1.8+)
`mp_catalog.py`, the API in [api.md](api.md#catalog-films-instead-of-folders-agent-18-featurescatalog).
1. A scan (at start, every 30 min and after `mp_library.invalidate()`) walks the library folders. Every top-level
   entry is one title; a folder with several big videos and no episodes is split into one title per file. Series
   are one title with an episode count; their main video is the first episode (agent 1.10+, `mp_ident.episodes`), not
   whatever sorts first (an "Extras" folder did).
2. Each new or changed title: `mp_ident` guess (with `.nfo`), ffprobe for audio languages (cached by size and
   mtime), subtitle languages from the sidecar names. Only known 2-letter codes are kept, the rest is `?`.
3. Wikidata in batches of 40 by IMDb id (SPARQL for the QID, `wbgetentities` for claims), refreshed every 30 days;
   labels of people and topics are cached. Title, rating, votes and runtime come live from the IMDb index, so an
   index refresh reaches old items without a rescan.
4. The Polish title is shown only when the film has Polish audio or subtitles.
5. A person and "what next" also ask Wikidata which films have that person or topic (cached for a day, run in
   parallel), and the IMDb index ranks the ones that are not on the disks by votes. The agent never searches for
   them; the user's own search addresses (`search_engines`, none by default) are opened by the phone.

Results are in `catalog.json` next to the configuration; `GET /api/catalog?since=<version>` returns only `same`
when nothing changed.

### Series (agent 1.10+)
`GET /api/library/episodes` walks a series folder (3 levels) and `mp_ident.episodes()` orders it:
- the number: `S01E02`, `1x02`, `Season 1 Episode 2`, `1.2` between dashes, otherwise the season from the name or a
  folder (`Season 4`, `Sezon 4`, `S04`) and the episode from `E07`/`Episode 7`, a leading number (`1-Title`) or a lone
  one (`Show_01_Title`); `(?<![A-Za-z0-9])` instead of `\b`, since `_` counts as a word character;
- extras: folders like Extras, Featurettes, Deleted Scenes, Dodatki…, names with sample/trailer/featurette/gag reel,
  and small files without a number (under 15% of the biggest video);
- order: season, episode, then natural name order; files with no number at all (specials, films of the series) last.

The phone keeps its own history (`pos:`, `pt:` and, new, `wd:` = watched to the end). "Play" on a series continues:
the episode with the latest activity if it has a position, else the one after it, else the first. The player gets
the rest of the list. History keys carry the file name: when re-encoding changes only the extension (.avi → .mkv),
the app moves the saved position and "watched" to the new name on the first read (`Settings.follow`).

### Intro and end credits (agent 1.11+)
`mp_segments.py`, `GET /api/library/segments`, asked by the player for a series episode (started from the catalog or
by Next episode). Idea of Jellyfin's Intro Skipper, no network and no model:
- intro: a chromaprint fingerprint (ffmpeg's `chromaprint` muxer, a 32-bit point every ~0.124 s) of the first 10
  minutes (at most 30% of the video) against the episodes before and after it in the same folder; candidate shifts
  from equal points, then the longest run of similar points (≤ 6 differing bits, gaps < 3.5 s) of 10–180 s. Two
  neighbours that point to different places (a "previously on" recap) give nothing; one that finds nothing (a pilot)
  leaves the decision to the other;
- credits: the same comparison of the last 8 minutes, the run must reach the last minute; otherwise `blackdetect` on
  keyframes only (about a second): a black stretch of ≥ 15 s reaching the last 30 s (credits on black);
- about 5–10 s per episode on jmpc, nice'd, one at a time; the first answer is `pending` (the app asks again every
  15 s), the next episode is computed right after; results in `<cache>/segments.json` (path, size, mtime),
  fingerprints in `<cache>/prints/`. Measured on 9 series: intros of 30–90 s found wherever the show has one (not
  in a reality show without a theme), credits by sound or black in most episodes.

The player shows "Skip intro" bottom right while the intro plays, and the Next episode card in the last 90 s or from
the credits; its countdown starts at the credits when they are known, otherwise at the end of the file.

The catalog is kept on the phone too (`files/catalog/<server>.json`, the agent's last answer, ~0.5 MB for a few
thousand titles): Library opens on it when the server is unreachable (a bar says since when), and every refresh asks
`?since=<version>`, so an unchanged catalog costs one tiny answer. Per-item changes aren't worth it at this size.
Before that refresh the phone asks `/api/library/moves?since=` (agent 1.14+): every move a task made (freeing a disk,
load balancing) is journalled in `moves.json`, and the phone re-keys what it keeps under a library folder and path
(positions, "watched", buffer use, a folder's audio choice and subtitle shift) to the new place.

"Similar in your collection" on the title screen is computed by the app from the catalog: shared actors and
directors, then topics, then genres. Genres alone need two specific ones in common ("Drama" doesn't count), and
kids' titles are matched only with kids' titles. The same match makes Discover's "Because you watched <title>" row
from the title watched last on this phone (finished films too), minus what was watched. The shared want-to-watch list is re-read every 30 s and on every
screen change, so a title added on another phone shows up without a restart.

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
   library. The history (`uploads.json`) keeps who sent it; Downloads in the app offers "Keep here" or "Move" to a
   library folder suggested from recognition (agent 1.8+).
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
| `TvQueue.kt` | a series sent to a screen, TV or Chromecast: the following episodes (from the agent, or saved with a downloaded copy), intro and credits; Remote shows "Skip intro" and "Next episode", from the credits (or the last 10 s) a 10 s countdown in Remote and in a notification starts the next episode on the same target; `RemoteSessionService` feeds it the target's state every 1.5 s (also the Cast SDK's Chromecast) and a CPU lock keeps it running near the end with the phone locked; from halfway the next episode's subtitles are fetched ahead. `Screens` (`ServersUi.kt`) puts the subtitles picked last first (or none on for "Off", `sub_lang` for mpv) and, since a screen cannot add subtitles later, downloads missing ones before sending: in the same series on its own, elsewhere after a question (`SubsAsk`) |
| `Offline.kt` | downloaded copies (`OfflineStore`) and `OfflineSync`: besides the bytes a copy keeps the agent's subtitles as they are now (fitted, shifted, the version in use), intro and credits and the series' episodes, saved when the download starts and completes and after the subtitles change, so the phone can play it or feed a TV without the computer |
| `LibraryTab.kt` | the library (folders of every server, tag filter, search, covers), folder settings (tags, visibility, storage profile, "Also copy to") and picking a folder on the computer |
| `LibraryJobs2.kt` | agent 1.17+ (plan stage 2): the Folder layout (levels in tap order, tree preview, age mapping, hand-arranged folders confirmed before the first preview), Balance between disks (goal, now → after per disk, rules) and For Chromecast screens on the F6 template of `LibraryJobs.kt`, and Background work (C4: running cards with pause/resume and the inline cancel confirmation, queue with ↑ and ✕, recent tasks with "Empty the trash and retry") |
| `LibraryTasks.kt` | agent 1.6+: "Disks and tidy-up" (disks with the library vs other files, trash, tasks with progress and cancel), the plan preview with checkboxes (free up space, move, tidy, shrink, duplicates, delete), profile choice, covers (`Covers`, `CoverThumb`) |
| `Catalog.kt` | agent 1.8+: catalog models and requests (`/api/catalog*`, wishlist, search engines, `/api/uploads`), `CatalogCache` (per server, in memory, refreshed with `?since=<version>`), "continue watching" from local positions, filter conditions (`Cond`) |
| `CatalogUi.kt` | covers: the agent's picture (a poster as it is, a frame with the title over a dark fade; agent 1.6+), a drawn one while it loads and when there is none (gradient by first genre, title in heavy type, greyscale when not on the disks); cover cards and rows, people, genre names, badges; series: `continueIndex` (where this phone left off), `playEpisode`, `playItem` |
| `CatalogScreens.kt` | the Library on a server with a catalog: Discover / Browse / Folders switch (Folders is the folder view above), results with combinable filters, title, missing title (want to watch, the user's own search engines, paste magnet), person, "What next?" (also opened by the player after a film ends), want-to-watch list, the search engines dialog; screens are a saveable stack inside the Library tab |
| `CatalogSearch.kt` | D1: the search field shared by the Library's tabs, results in groups "on your disks", "you don't have it · from the film database" (`/api/catalog/search`, the agent's IMDb index, agent 1.17+) and people; the search sites row of a missing title's page (D2) |
| `UploadsUi.kt` | "Sent from phones" in the Downloading tab: files waiting to be put away (Move, Other folder…, Keep here), moving progress, "Put away this week", the badge on the tab |
| `Tasks.kt` | the "finished" notification of a followed file task (checked with `Access` while the app is on screen, when the ping says a task finished) |
| `TaskWatch.kt` | tasks this phone follows (started here or "Monitor") and the service with their progress notification |
| `MediaUi.kt` | Phone, screen and VLC buttons |
| `SubsSheet.kt` | the subtitle sheet, one for the player (CC), the title card and the Remote: languages (tap = the language's version in use on, › = its versions), "Download: <language>" for the user's languages that are missing (searches that language, downloads the best results until one fits, at most 3) and "another language…"; one language: the versions on the computer (tap = it becomes the one in use, `POST /api/subs/default`), built-in tracks, and a search in that language where results the user already has are marked "you have these" and picked without downloading |
| `PlayerActivity.kt` | the player on the phone; a series played from the catalog carries the following episodes (ExoPlayer's ⏭ opens the next one: the ForwardingPlayer reports "next" as available and turns it into `playNext()`; our actions are icons in ExoPlayer's style in its bottom bar before CC: computer screen, open in VLC, with a tooltip on a long press; the top bar holds only the title and the reserve; in the last 90 s a card bottom right, independent of the controls, checked every second by position, not only by the "ended" event; a 10 s countdown with Play now / Cancel from the end of the file, or from the credits when known, standing while paused; seeking back hides the card; each episode opens a new player); the CC button is ours (ExoPlayer's is hidden, because it is disabled when a video has no subtitles) and opens `SubtitlesSheet` over the player with Off and the video's own tracks added; the film pauses while the sheet is open, and also while ExoPlayer's gear menu (audio track, speed) is open: that popup takes the window focus, so playback resumes when the focus comes back; a picked file reloads the video at the same position with that file as the default track; a new file is not added to a downloaded copy's saved subtitles); the subtitles picked last are remembered (a language or Off) and the next video starts with them; when that language is missing (no file, no embedded track) the player gets it (`fetchSubtitles`, the same as "Download: <language>") on its own in the folder where it was picked (a series, also after skipped episodes), and elsewhere shows a bar over the picture "No subtitles in <language> — Download / No" for 12 s without pausing; from halfway through an episode the next one's subtitles in that language are fetched ahead, the same provider first; "Sync while watching" in the sheet puts ±0.1/±0.5 s, Reset and Done buttons over the picture, the cues are drawn by the app from the file with the pending shift so the effect is visible at once, and 8 s without a tap saves it (`POST /api/subs/shift`); that shift is remembered per folder and provider and applied to subtitles downloaded without the user there) |
| `AudioSheet.kt` | the audio sheet (a button next to CC in the player, "Audio" in the mpv Remote): tracks grouped by language with their kind read from the title (lector, dubbing, original, audio description, commentary), channels and codec; a pick is remembered as a kind and a language (`AudioPref`), per folder (a series) and in general, and the next video picks the matching track (`pickAudio`); a video sent to mpv carries it as `audio` in `/api/player/open` (agent 1.13.3+, `pick_audio` in the agent). Chromecast and DLNA play the file's default track |
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
