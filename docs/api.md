# Agent HTTP API

Address: `http://<computer>:8765`. Everything except `/api/ping` and `/api/pair` requires a device token in the
`Authorization: Bearer <token>` header or in the `?token=` parameter (the latter for players that can't set
headers). A device token comes from pairing (below); the old shared `token` from `config.json` still works as the
"legacy" device until someone disables it. Responses are JSON, and errors look like `{"error": "..."}` with a 4xx or
5xx status.

**Message language.** User-facing text in responses (`error`, `reason` in `power.wake`, `problems` in the Chromecast
assessment, subtitle provider states, the Chromecast profile name) is in English or Polish depending on the
`Accept-Language` header: the highest-ranked language `pl*` → Polish, otherwise (also without the header) English.
The app sends the phone's language, the web page sends `navigator.language`, and the browser adds this header on
its own anyway. Background jobs (subtitle search, conversion) write in the language of the request that started
them. JSON keys and state values (`state`, `verdict` etc.) don't change. Agent logs stay in Polish.

`/media`, `/lib` and `/sub` accept `?ticket=` from `POST /api/media/ticket` instead of a token: a signature for one
file, valid for 12 h. This is how another server (a screen) gets a video without being paired with the file server.
A ticket doesn't work for the API.

Responses from `/media`, `/lib` and `/sub` carry `Access-Control-Allow-Origin: *` (plus `OPTIONS` for preflight),
because the Chromecast receiver reads the video and subtitles from a different origin. Without it, subtitles are
skipped.

`ref` means a file reference: `torrent_id` + `file_index`, `root` + `path` or `playing: true`
(see [architecture](architecture.md#file-reference-ref)). In GET it is passed as query parameters, in POST in the
JSON body.

## Web client

| method and path | description |
|---|---|
| `GET /` | the `web/index.html` page, without a token (the data goes through the API with a token anyway) |

## System

| method and path | description |
|---|---|
| `GET /api/ping` | `{"ok": true, "app": "magneticplayer", "version", "id"}`, without a token. `id` lets the app check that the same server is at the address before it sends the token |
| `GET /api/info` | `id`, `name`, `api` (API level, currently 2), `hostname`, `version`, `backend`, `keep_awake` (reason sleep is blocked), `iface`, `ip`, `mac`, `wake_on`, `wireless`, `device` (who is asking), `features` and `power` (below) |
| `POST /api/wake/verified` | the app has woken this server over the network; the agent stores this in the config (`wake_verified`) and from then on reports waking as `yes` to all devices |
| `POST /api/wake` | `{"mac", "ip"?}`: sends a magic packet from this server (waking another server when the phone can't reach it) |
| `POST /api/system/suspend` | closes mpv and suspends the computer |
| `POST /api/system/screen_on` | wakes the computer's screen and, with `auto_unlock`, also removes the lock screen → `{"unlocked"}` |
| `POST /api/system/unlock` | wakes the screen and removes the lock regardless of `auto_unlock` |
| `GET /api/app/version` | `{"version_code", "version_name"}` of the published app or `{"version_code": 0}` |
| `GET /app/MagneticPlayer.apk` | the published app file |

`features`: `torrent` (`"deluge"`, `"transmission"` or `null`), `library`, `player` (mpv + graphical session, unless
config `player: false`), `subs_search`, `unlock`, `apk`, `wake_others`, `upload` (accepts videos from the phone,
below), `install` (installs the agent on another computer, below). `power`: `suspend`, `poweroff` (both `false`
with config `power: false`), `wake` (`confidence: "no"` with config `wake: false`, light install)
(`{"method": "wol"|null, "mac", "confidence": "maybe"|"no", "reason"}`). Only the app knows `yes` for `confidence`,
once waking has worked; the agent reports `yes` after `POST /api/wake/verified`. Wakeup counters from
`/sys/class/wakeup` don't help: on the jmpc motherboard (ASUS H110M-A) every wakeup is attributed to the power
button.

## Pairing and devices

| method and path | description |
|---|---|
| `POST /api/pair` | without a token: `{"code", "name"}` → `{"token", "device_id", "role", "expires"}`. A code works once, for 10 min (a guest code: one day); after 10 bad attempts per minute, refused |
| `POST /api/pair/code` | a new code for another device → `{"code", "expires_in", "host", "port", "id", "name"}`; with `{"role": "guest", "days": N}` a code for a guest (`days` = how long access lasts, 0 or missing = no limit) |
| `GET /api/devices` | `[{id, name, created, last_seen, current, role, expires, legacy?}]` |
| `POST /api/devices/revoke` | `{"id"}`; `"legacy"` disables the old shared token |
| `POST /api/media/ticket` | `ref` (+ `"variant": "cast"`) → `{"url", "subs": [{name, lang, url, vtt}], "title", "expires", "variant", "content_type"}`; URLs with a ticket, host from the `Host` header; `vtt` is the subtitles converted to WebVTT (for Chromecast); with `variant: "cast"` the URL points to the Chromecast version if there is one (`&v=cast`, `content_type: video/mp4`) |

A **guest** (`role: "guest"`, agent 1.3+) only has an allow-list of endpoints (`GUEST_ALLOWED` in the agent): info,
Library, the torrent list and their files, video and subtitle files, tickets, format and Chromecast versions, the
screen (`player/*`, `screen_on`), Chromecast, subtitles, waking other servers. Everything else gets `403` ("a guest
doesn't have access to this"): adding torrents and torrent actions, library and OpenSubtitles settings, `fs`,
suspend, unlock, uploads, pairing and the device list, installation. `/api/info` tells a guest `role: "guest"` and
has `upload`, `install`, `unlock`, `suspend` and `poweroff` set to `false` for them. After `expires` the token stops
working (`401`).

`/media` and `/lib` with `&v=cast` serve the Chromecast version (when there is one), and with `&cap=<subtitles>` and
the `getcaptionInfo.sec` request header they answer with a `CaptionInfo.sec` header holding the URL of those
subtitles (Samsung TVs, DLNA).

## File format, Chromecast version (agent 1.2+)

| method and path | description |
|---|---|
| `GET /api/media/probe?ref` | `{"ffmpeg", "file": {container, duration, video, audio[], subs[]}, "cast": {verdict: ok\|maybe\|no, problems[], video_copy, audio_copy}, "complete", "variant": {size, cast} \| null, "job", "encoder": {encoder, label, hw}, "cast_devices": [{id, name, label, verdict, problems}]}`; `cast` is the overall assessment (the most cautious one), `cast_devices` the assessment for each Chromecast with a known model |
| `POST /api/media/convert` | `ref` → job state (`state: queued\|running\|done\|error`, `pct`, `speed`, `encoder`, `error`, `finishing`: ffmpeg has finished encoding and is moving the index to the start of the file, which takes up to a minute on a slow disk); `"cancel": true` aborts, `"remove": true` deletes the version; 409 when the torrent isn't fully downloaded |
| `GET /api/media/converts` | all jobs since the agent started |

The version sits next to the video in `.mp-cast/<name>.cast.mp4`. H.264 8-bit ≤1080p video is kept, anything else
goes through the best working encoder: NVENC, VideoToolbox, VAAPI, Quick Sync, V4L2 or x264, at 1080p. AAC/MP3
audio is kept, anything else → AAC stereo. `features.transcode` in `/api/info` is the encoder label, and
`transcode_hw` says whether it is hardware.

## Chromecast controlled by the agent (web client, Remote in the app)

The agent listens to all Chromecasts on the network, so the status also shows what someone else started (a phone via
the Cast SDK, YouTube, Spotify). The app uses this when it has no Cast session of its own.

| method and path | description |
|---|---|
| `GET /api/cast/devices` | `[{id, name, model, product, profile, label}]` (mDNS via pychromecast; the first call waits up to 3 s); `product` = `product_name` from `eureka_info`, `label` = the model name from the profile (`docs/devices.md`) |
| `GET /api/announce` | what other remotes on the network are playing (multicast announcements on `239.255.77.77:47700` from phones and other agents, no entries older than 10 s): `[{target: {kind, id, name}, from: {kind, id, name}, title, state, pos, dur}]`; the agent itself announces mpv on its own screen (`docs/devices.md`) |
| `GET /api/renderers` | registry of Chromecasts seen on the network (`renderers.json`): `id`, `name`, `model`, `product`, `profile`, `build`, `host`, `first_seen`, `last_seen` |
| `POST /api/cast/play` | `ref` + `device` (+ `start`) → plays (the Chromecast version if there is one; VTT subtitles) |
| `GET /api/cast/status` | `{active, device, id, app, mine, title, state, pos, duration, volume, muted, volume_fixed, all}`: the first playing Chromecast (those started by the agent first), `all` = all playing ones; `app` = the app on the Chromecast ("Default Media Receiver", "YouTube"…), `mine`: started by the agent; `volume` in percent, `volume_fixed`: fixed volume (the buttons disappear) |
| `POST /api/cast/cmd` | `{"cmd": "toggle\|play\|pause\|seek\|seek_rel\|volume\|mute\|stop", "value", "device"}`; without `device` it acts on the Chromecast from the status; `volume` in percent 0–100, `seek_rel` in seconds; `stop` closes the app on the Chromecast (someone else's too) |

From the console: `magneticplayer_agent.py --pair` (code + QR in the terminal), `--devices`, `--revoke ID`.

## Torrents and jobs

| method and path | description |
|---|---|
| `GET /api/torrents` | list: `id`, `name`, `progress`, `state`, speeds, `eta`, `size`, `sequential`, `peers`, `mine` (sent from the app), `after` |
| `POST /api/torrents` | `{"magnet", "sequential": true, "after": "none\|stop\|suspend", "root"?, "sub"?, "guess"?}` → `{"id", "path"}`; `root`/`sub` (agent 1.5+): download straight into a library folder (the subfolder is created), `guess`: the recognition from `/api/library/guess`, so a change can be remembered |
| `POST /api/torrents/<id>/move` | `{"root", "sub"?, "learn"?}` (agent 1.5+): the client moves the torrent (it keeps seeding); `learn`: next time this title goes to the folder with this tag |
| `GET /api/torrents/<id>/files` | files: `index`, `path`, `size`, `progress`, `media`, `ready` (contiguous bytes from the start), `duration`, `ready_sec` |
| `POST /api/torrents/<id>/action` | `{"action": "pause\|resume\|remove\|remove_data\|sequential_on\|sequential_off"}` (depending on the backend) |
| `POST /api/torrents/<id>/after` | `{"after": "none\|stop\|suspend"}` |
| `GET /api/jobs` | job history: `id`, `name`, `added`, `after`, `after_done`, `magnet`, `in_client`, `progress`, `state` |
| `GET /media/<id>/<index>[/name]` | stream of a torrent file (Range), downloaded pieces only: on a missing piece it waits up to 20 s, then drops the connection. The `X-Ready-End` header (also in `HEAD` and `/lib`): up to which byte it can be read from the start of the range |

## Library

| method and path | description |
|---|---|
| `GET /api/library` | folders visible to the device: `index`, `id`, `name`, `path`, `exists`, `tags`, `visible`, `exclude`, `open`; `?manage=1` (household member): also those hidden from them and those tagged `kopia` (backup) (`open: false` when they can't open them) |
| `POST /api/library` | `{"path", "name"?}` adds a folder, `{"op": "remove", "index"}` removes it, `{"op": "update", "index", "name"?, "tags"?, "visible"?, "exclude"?}` changes it (agent 1.4+, `features.library_tags`); returns the list as with `?manage=1` |
| `POST /api/library/guess` | `{"magnet"\|"name"\|"torrent"}` (agent 1.5+, `features.library_guess`) → `name`, `guess` (`tag`: filmy/seriale/bajki/muzyka/inne/null, i.e. movies/series/kids/music/other, `kind`, `title`, `year`, `imdb`, `season`, `genres`, `confidence`: high/medium/low, `why`), `targets` (writable folders without `kopia`: `root`, `id`, `name`, `path`, `tags`, `free`, `sub`, `existing`) and `pick` (an index into `targets` or null). Series: an existing subfolder with its title, otherwise a new `<title>`; movie: the folder with the tag that has the most free space |
| `GET /api/library/search?q=` | (agent 1.5+, `features.library_search`) folders and media files whose name contains every word of `q` (case and diacritics ignored), only in folders the device sees, without excluded subfolders and `kopia`: list of `root`, `root_name`, `path`, `dir`, `size`, `media`, max 100. Names are cached for 5 minutes |
| `GET /api/library/browse?root=&path=` | `root`, `path`, `dirs`, `files` (`name`, `size`, `mtime`, `media`, `subs`) |
| `GET /api/fs?path=` | subfolders for choosing a new folder (`parent`, `dirs`) |
| `GET /lib/<root>/<path>` | stream of a library file (Range) |

## Uploading from the phone

Resumable file upload (app: videos downloaded by the browser, [plan-upload.md](plan-upload.md)).
`<id>` is chosen by the phone (8–80 characters `A-Za-z0-9_-`, fixed per file). Works without Deluge and
Transmission.

| method and path | description |
|---|---|
| `GET /api/upload/<id>` | `{"received", "size", "done", "name"}`; `404` with `received: 0` when the server knows nothing about it |
| `PUT /api/upload/<id>?name=&size=&offset=` | the body (Content-Length, may be 0 = just a check) is appended from `offset` → `{"received", "size", "done", "name"}`. The last chunk completes the file, and `name` is then the final name (on a collision "video (2).mkv"). Errors: `409` with `received` when `offset` doesn't match (and when the same file is coming over another connection), `507` when out of space (512 MB is kept in reserve), `400` when a chunk goes past `size` |

Chunks go to `<folder>/.mp-upload/<id>.part` and are written as a stream (not in memory). An interrupted connection
keeps what arrived, and after 60 s of silence the agent closes it. `name` is the bare file name: folders, control
characters and a leading dot are stripped. Target folder: `upload_dir` in `config.json`, and without it
`Z telefonu` ("From phone") in the first existing library folder. Without either, `features.upload` is `false`.

## Access requests (agent 1.3+, `features.access_requests`)

| method and path | description |
|---|---|
| `POST /api/access/request` | without a token, only from a private address: `{"name"}` → `{"id", "secret", "expires_in"}` (300 s); another request from the same address replaces the previous one; `429` with more than 5 pending or 10 per minute |
| `GET /api/access/request/<id>?secret=` | without a token: `{"state": pending\|approved\|delivered\|denied, "expires_in"}`; with `approved`, once, `"code"` (a pairing code for `POST /api/pair`) and `"role"`; `404` after expiry or with a wrong secret |
| `GET /api/access/requests` | household member: `{"requests": [{id, name, ip, age, expires_in}]}` |
| `POST /api/access/requests/<id>` | household member: `{"action": "approve"\|"deny", "role": "guest"\|"owner", "days"}` (guest: `days` limits access as with an invitation); `409` when already decided or expired |

Requests live in the agent's memory (`mp_access.py`); a restart clears them.

## Installing on another computer (agent 1.3+)

| method and path | description |
|---|---|
| `POST /api/install/code` | a one-time join code (30 min) → `{"code", "expires_in", "url", "command", "command_full", "command_windows"}`; `command` is `curl -fsSL <url> \| bash` (light install), `command_full` with `?full=1` (full, with the administrator password), `command_windows` is `irm <url>/ps \| iex` (PowerShell, light); `409` when there are no install scripts next to the agent |
| `GET /api/install/status?code=` | only for the device that created the code: `{"state": waiting\|downloading\|joined, "server": {host, port, id, name, pair_code, version} \| null, "downloads", "expires_in"}` |
| `GET /i/<code>[?full=1]` | without a token: a bash script that downloads the bundle and runs `install.sh` with `JOIN=<url>` |
| `GET /i/<code>/ps` | without a token: a PowerShell script that downloads the bundle (`tar` from Windows 10+) and runs `install.ps1` with `MP_JOIN=<url>`; `409` when the server has no `install.ps1` |
| `GET /i/<code>/bundle.tar.gz` | without a token: the agent from this server (`server/*.py`, `web/`, `install*.sh`, `uninstall.sh`, `install.ps1`, `uninstall.ps1`; no venv and no APK) |
| `POST /api/install/join` | without a token, from the new agent (`--join`): `{"code", "id", "name", "port", "pair_code", "version"}`; the address is taken from the connection. Uses up the code |

Codes live in the agent's memory (a restart clears them). A bad, used or expired code gives `403`, and after 10 bad
attempts per minute `429`. After the new server registers, the code lives for at least another 10 min (as long as
the pairing code is valid), so the phone has time to collect it.

## Player on the computer (mpv)

| method and path | description |
|---|---|
| `POST /api/player/open` | `ref` + optionally `start` (seconds); or a video from another server: `{"url", "subs": [url…], "title", "start"?}` |
| `GET /api/player/status` | `running`, `title`, `path`, `pos`, `duration`, `paused`, `volume`, `muted`, `fullscreen`, `sub`, `audio`, `tracks[]` (`id`, `type`, `title`, `lang`, `codec`, `selected`, `external`, `default`, `forced`, `demux-channel-count`, `demux-w`, `demux-h`) |
| `POST /api/player/cmd` | `{"cmd", "value"?}`, commands below |

Commands:
- playback: `toggle_pause`, `pause`, `play`, `seek` (s, relative), `seek_to` (s), `stop`;
- picture and sound: `fullscreen` (toggles without `value`), `volume`, `volume_rel`, `mute`;
- tracks: `track` (`{"type": "video|audio|sub", "id": N|"no"}`), `sub_cycle`, `audio_cycle`, `sub_delay` (s).

## Subtitles

| method and path | description |
|---|---|
| `GET /api/subs/list?<ref>` | subtitles next to the video: `name`, `lang`, `ext` |
| `GET /sub?<ref>&name=[&vtt=1]` | the subtitle file. With `vtt=1` the agent converts SRT to WebVTT (for `<track>` in the browser) |
| `POST /api/subs/search` | `ref` + `languages: "en,pl"`, optionally `title` and `year` (manual correction) → `query`, `title`, `year`, `providers`, `results[]` (`id`, `provider`, `lang`, `release`, `score`, `hash_match`, `hi`, `title_match`), `hidden`. With `"async": true` it returns `{"job"}` right away |
| `POST /api/subs/job/stop` | `{"id"}`: "That's enough", don't wait for slow providers and skip the second round |
| `GET /api/subs/job?id=` | progress: `phase` (`file`/`search`/`match`/`done`), `round` (2 = title from `[brackets]`), `query`, `matched`/`total`, `providers` (`state`: `pending`/`running`/`done`/`skip`/`error`, `count`, `error`, `secs`), `elapsed`, `done`, `result` or `error` |
| `POST /api/subs/download` | `ref` + `id`, `provider`, `languages` (and the same `title`/`year` as for the search) → `{"name", "provider"}`. If the video is playing in mpv, the subtitles are turned on right away |
| `GET /api/config/app_updates` | `{"mode": "auto"\|"manual", "last_check", "last_error", "published": {"version_code", "version_name"}}`: the phone app from GitHub releases |
| `POST /api/config/app_updates` | `{"mode": "auto"\|"manual"}`; switching to `auto` checks right away |
| `GET /api/config/agent_updates` | `{"mode", "supported", "version", "available", "state": "idle"\|"downloading"\|"waiting"\|"installing"\|"failed"\|"error", "waiting_for": "stream"\|"conversion", "last_check", "last_error", "last_result": {"version", "ok", "error", "time"}}`: the agent's own updates (agent 1.6+) |
| `POST /api/config/agent_updates` | `{"mode": "auto"\|"manual"}`; switching to `auto` checks right away |
| `POST /api/agent/update` | check now and install a newer agent at the next quiet moment (also a version that was rolled back before); 409 where unsupported |
| `GET /api/config/opensubtitles` | `api_key` (whether set), `username`, `password` (whether set) |
| `POST /api/config/opensubtitles` | `{"api_key"?, "username"?, "password"?}`; empty fields don't overwrite saved ones |
