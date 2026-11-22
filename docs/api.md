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
| `GET /api/ping` | `{"ok": true, "app": "magneticplayer", "version", "id"}`, without a token. `id` lets the app check that the same server is at the address before it sends the token. Agent 1.14.1+: `tasks`, when the latest library task finished (epoch s, 0 = none): the app reads `/api/tasks` for its "task finished" notifications only when this changes |
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
| `POST /api/media/ticket` | `ref` (+ `"variant": "cast"`) → `{"url", "subs": [{name, lang, default, url, vtt}], "title", "expires", "variant", "content_type"}`; URLs with a ticket, host from the `Host` header; `vtt` is the subtitles converted to WebVTT (for Chromecast); with `variant: "cast"` the URL points to the Chromecast version if there is one (`&v=cast`, `content_type: video/mp4`) |

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
| `GET /api/media/probe?ref` | `{"ffmpeg", "file": {container, duration, video, audio[], subs[]}, "cast": {verdict: ok\|maybe\|no, problems[], video_copy, audio_copy}, "complete", "variant": {size, cast} \| null, "job", "encoder": {encoder, label, hw}, "cast_devices": [{id, name, label, verdict, problems}]}`; `cast` is the overall assessment (the most cautious one), `cast_devices` the assessment for each Chromecast with a known model; agent 1.14.2+: `variant.audio` `{"lang", "title"}`, the one audio track of the prepared version (a phone offers preparing it again when the language picked since differs); agent 1.18+ (`features.convert_audio_match`): `?audio=<kind|lang>` (the remembered choice, as in `/api/player/open`) adds `audio_want` `{index, lang, title, kind, codec, channels}` (the film's track a version made now would carry: the choice's track, else Polish, else the default) and `variant.audio_match` (whether the prepared version carries that track; only when the film has two or more audio tracks and the version's track is known); `variant.audio` also gets `index` (the film's track it came from, noted in `.mp-cast/<name>.cast.mp4.json` when the version is made, or matched by language and title for older versions) and `kind` (lector, dubbing, original, description, commentary or "", read from the title as the app does) |
| `GET /api/media/title?ref` | (agent 1.18+, `features.media_title`, guests too) `{"title"}`: a library video's recognised name as mpv and the Remote show it ("Series · S2 E3", "Film (1968)", `Catalog.title_for`), `null` when the catalog doesn't know it; the phone's player shows it instead of the file name |
| `POST /api/media/convert` | `ref` → job state (`state: queued\|running\|done\|error`, `pct`, `speed`, `encoder`, `error`, `finishing`: ffmpeg has finished encoding and is moving the index to the start of the file, which takes up to a minute on a slow disk); `"cancel": true` aborts, `"remove": true` deletes the version; 409 when the torrent isn't fully downloaded; agent 1.14+ (`features.convert_audio`): `audio` (a remembered choice as in `/api/player/open`, e.g. `"|ja"`) picks the audio track of a Chromecast variant |
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
| `GET /api/library` | folders visible to the device: `index`, `id`, `name`, `path`, `exists`, `tags`, `visible`, `exclude`, `open`, `profile`/`profiles` (storage profiles), `replicate_to` and `replica` (`pending`, `copied`, `last`, `error`); `?manage=1` (household member): also those hidden from them and those tagged `kopia` (backup) (`open: false` when they can't open them) |
| `POST /api/library` | `{"path", "name"?}` adds a folder, `{"op": "remove", "index"}` removes it, `{"op": "update", "index", "name"?, "tags"?, "visible"?, "exclude"?, "replicate_to"?}` changes it (agent 1.4+, `features.library_tags`; `replicate_to`: another folder's `id` or null, agent 1.6+); returns the list as with `?manage=1` |
| `POST /api/library/guess` | `{"magnet"\|"name"\|"torrent"}` (agent 1.5+, `features.library_guess`) → `name`, `guess` (`tag`: filmy/seriale/bajki/muzyka/inne/null, i.e. movies/series/kids/music/other, `kind`, `title`, `year`, `imdb`, `season`, `genres`, `confidence`: high/medium/low, `why`), `targets` (writable folders without `kopia`: `root`, `id`, `name`, `path`, `tags`, `free`, `sub`, `existing`) and `pick` (an index into `targets` or null). Series: an existing subfolder with its title, otherwise a new `<title>`; movie: the folder with the tag that has the most free space |
| `GET /api/library/search?q=` | (agent 1.5+, `features.library_search`) folders and media files whose name contains every word of `q` (case and diacritics ignored), only in folders the device sees, without excluded subfolders and `kopia`: list of `root`, `root_name`, `path`, `dir`, `size`, `media`, max 100. Names are cached for 5 minutes |
| `GET /api/library/browse?root=&path=` | `root`, `path`, `dirs`, `files` (`name`, `size`, `mtime`, `media`, `subs`) |
| `GET /api/library/cover?root=&path=[&w=720]` | (agent 1.6+, `features.library_covers`, guests too) a cover for a folder or a video: `poster`/`folder`/`cover`/`movie`/`show` `.jpg\|.png\|.webp` in a folder, `<name>.jpg`/`<name>-poster.jpg` next to a video, otherwise a frame of the (biggest, not an extra) video, 360 px wide (`w=720`: 720), cached in `~/.cache/magneticplayer/covers`; 404 = nothing. Agent 1.10+: the frame is a fifth in, without black bars, the most typical of ~3 s; header `X-Cover-Kind: frame` or `picture` |
| `GET /api/library/segments?root=&path=` | (agent 1.11+, `features.segments`, guests too) intro and end credits of an episode, found by comparing its sound with the neighbouring episodes of its folder: `{"state": "pending"}` while that runs (5–10 s; the next episode is queued too), then `{"state": "done", "intro": [start, end] or null, "credits": start or null}` in seconds; `unavailable` without ffmpeg/numpy |
| `GET /api/library/episodes?root=&path=` | (agent 1.10+, `features.library_episodes`, guests too) a series folder: `episodes` in watching order and `extras`, each `path` (relative to the library folder), `name`, `season`, `episode` (null when the name has none), `size` |
| `GET /api/fs?path=` | subfolders for choosing a new folder (`parent`, `dirs`) |
| `GET /lib/<root>/<path>` | stream of a library file (Range). Agent 1.15.2+: a file of a torrent still downloading into the library is served like `/media/` (downloaded pieces only, `X-Ready-End`); before, the undownloaded parts went out as zeros and a phone kept them as a "whole" copy. Every stream (`/lib`, `/media`) also sends `Last-Modified` (the file's mtime) and, for a torrent still downloading, `X-Downloading: 1`: the app keeps the stamp with a downloaded copy and takes the copy again when the file changes |

## Disks, background tasks, storage profiles (agent 1.6+, `features.library_tasks`)

Household members only. Nothing changes without a preview: a **plan** proposes items, a **task** carries out the
chosen ones in the background (one at a time, persisted in `tasks.json`, continued after an agent restart).

| method and path | description |
|---|---|
| `GET /api/library/disks` | `disks` (with library folders first, fullest first): `id`, `name`, `mount`, `total`, `free`, `used`, `system` (1.8+: the system disk), `home` (1.8+: where "add folder" starts browsing), `roots` (`root`, `id`, `name`, `tags`, `size`, `files`, `profile`, `profiles`; empty for disks without library folders, listed since 1.8: `/`, `/home`, `/media/*`, `/mnt/*`, `/run/media/*`, `/srv/*` of at least 8 GB); `trash` (`root`, `root_name`, `day`, `size`, `files`); `trash_days` |
| `GET /api/library/moves?since=<epoch s>` | agent 1.14+ (`features.library_moves`): `moves` made by tasks after `since` (`at`, `from_root`, `from`, `to_root`, `to`; folder numbers as they are now, paths relative to the folder; a moved folder covers everything under it) and `now` (pass it as the next `since`). Phones re-key their positions and "watched" marks with it |
| `POST /api/library/plan` | starts a planner, returns `{"id", "state": "running", "progress"}`. Kinds: `{"kind": "free", "root", "need"?}` move items off the disk of `root` to folders with the same tag on other disks (default: up to 15% free); `{"kind": "shrink", "root", "path", "profile"?, "encoder"?}` re-encode by storage profiles (`profile` is saved for that folder/subfolder/file first; `encoder`: `auto`/`cpu`/`gpu`, default the server's `shrink_encoder`, agent 1.7+); `{"kind": "modernize", "encoder"?}` (agent 1.8.2+, `features.library_modernize`) every video in an old picture format that phones and TVs no longer decode (DivX 3, WMV, RealVideo, Flash, Theora…) in all library folders except backups, re-encoded by the folder's profile (`original` and `tidy` count as `good`) even when that saves little, and allowed to grow up to 25%; the result is a `shrink` plan whose items carry `old_format: true`; since agent 1.15.0 also videos with a modern picture whose MP3/MP2/AC3 sound has damaged frames, items with `audio_fix: true`, `broken` (audio track indexes) and `audio_codecs` (e.g. `["MP3"]`): only those tracks are re-encoded to AAC, the picture is copied; the plan's `audio_unchecked` counts files the background check hasn't reached yet (also said in `warnings`); `{"kind": "subs", "every"?}` (agent 1.9+, `features.subs_fit`) videos with SRT/text SUB files to fit to their film, in all library folders except backups (files assessed before are left out unless `every`); items carry `subs` `[{"name", "lang", "locked"}]`, the result `minutes`; a finished item has `result` and a localized `note`, the task `fixed` and `unfit`; `{"kind": "subs_get", "languages": "pl,en", "root"?, "path"?}` (agent 1.13+, `features.subs_get_task`) videos without subtitles in these languages (no sidecar file, no track in the video; a sidecar without a language in its name counts by the language of its text, 1.13.2+) in one file or folder (`root` + `path`) or all library folders except backups (samples, trailers and extras folders left out, 1.15.3+); items carry `langs` (the missing ones); running an item searches, downloads the best match (the provider that worked for that folder first, then hash match and score, up to 3 tries) and fits it, `note` says what was saved; `{"kind": "tidy"}` items whose recognition doesn't match the folder's tag; `{"kind": "dupes"}` identical videos; `{"kind": "trash", "items": [{"root", "path"}]}` deleting to the trash; `{"kind": "move", "items": [{"root", "path", "to_root", "to_sub"?}]}` explicit moves |
| `GET /api/library/plan/<id>` | `state` (`running`/`done`/`error`), `progress`, `step` (the file or folder being looked at, while running; agent 1.15.3+), `result`: `kind` (`move`/`shrink`/`trash`/`dupes`), `title`, `items`, `bytes`, `skipped` (with `reason`); free: `need`, `free_before`, `free_after`, `total`; shrink: `est`, `saved`, `hours` (video length), `encoder` (label), `encoder_id` (`cpu`/`gpu`, 1.7+), `encoders` (1.7+: `id`, `label`, `note`, `bframes`, `est`, `saved`, `work_hours` = estimated hours of work or null), `warnings`, `trash_days`; tidy: `checked`; dupes: `groups` (`size`, `files`: `root`, `root_id`, `root_name`, `path`, `torrent` = still in the torrent client, the trash plan skips it, agent 1.15.3+), `wasted`. Items: `name`, `path`, `root`, `root_name`, `size`; move: `to_root`, `to_name`, `to_sub`, `torrent` (moved by the client), `extra` (subtitles etc. next to a single video), `tag`, `unsure`, `guess`; shrink: `profile`, `est`, `codec`, `height`, `to_height`, `duration`, `bitrate`. Plans live in memory for an hour |
| `POST /api/tasks` | `{"plan", "pick"?: [item indexes]}` → the task |
| `GET /api/tasks` | newest first: `id`, `kind`, `title` (in the language of the request; tasks created before 1.8.1 keep the language they were made in), `state` (`queued`/`running`/`done`/`cancelled`), `created`, `started`, `finished`, `total`, `done_bytes`, `pct`, `current`, `saved`, `errors`, `paused` (the encode is stopped: somebody streams with `shrink_while_watching: "pause"`, or a conversion runs; `HEAD` probes don't count), `encoder` (label, shrink tasks from 1.7), `note` (1.7+, localized: why it is slower, stopped or waiting to swap a film that is being watched), `items` (with `state`: `pending`/`running`/`done`/`error`/`skipped`, `error`, `saved`), `item_count`, `items_done` (1.14.1+). `?brief=1` (1.14.1+, `features.tasks_brief`) leaves `items` out: a finished task with hundreds of files is ~400 kB; `GET /api/tasks/<id>` still has them; agent 1.14.2+: `error_groups` `[{"error", "count"}]`, item errors grouped by reason (up to 5, most frequent first; a full disk reads "no space left on the disk" instead of ffmpeg's tail) |
| `GET /api/tasks/<id>`, `POST /api/tasks/<id>/cancel` | one task; stop it (the current item stays as it was) |
| `POST /api/tasks/<id>/remove` | agent 1.13+ (`features.task_remove`): a finished or cancelled task off the list; 409 for a queued or running one |
| `POST /api/library/profile` | `{"root", "path", "profile": "original"\|"tidy"\|"good"\|"compact"\|"tiny"}` (`path` empty = the whole folder); agent 1.16+ also `"mini"` (360p) and `"same"` (H.265 at the same resolution) |
| `POST /api/library/trash/empty` | delete everything in the trash now → `{"freed"}` |

### Task scopes, counters and new task kinds (agent 1.16+, `features.jobs_v2`)

`features.jobs_v2: true` means everything in this section exists; `features.jobs` lists the kinds the counters cover
(`free`, `dupes`, `audio_fix`, `modernize`, `downscale`, `compress`, `subs_get`, `subs`, `tags`, `trash`). Older
apps keep using the kinds and parameters above, which work as before. All of it is for household members only.

**Scope.** Every plan body and `GET /api/library/jobs` take an optional `scope` (a JSON object in the body, URL-encoded
JSON text in a query string); absent or `{}` is the whole machine (all library folders except backups and hidden ones):

```json
{"roots": [{"root": 0, "path": "Series/Pioneer One"}],
 "files": [{"root": 0, "path": "Movies/Sintel (2010)/Sintel.2010.mkv"}],
 "imdb": "tt1727587",
 "tag": "animation",
 "age": [0, 7]}
```

`roots` (folders by library folder index; `path` `""` = the whole library folder; several are ORed), `tag` and `age`
are filters combined with AND; `files` and `imdb` are exact sets, intersected with the filters when both are given.
`tag` matches a folder tag, the catalog title's grouping folder or one of its genres (case ignored); `age` is a list
of the groups 0, 7, 12, 16 and 18 (a catalog age rating under 6 is 0, under 12 is 7 and so on; a kids' title without a
rating counts as 0). A file without a catalog title never matches `imdb` or `age`. Malformed scopes → 400 with a
localized `error`. See `mp_scope.py`.

| method and path | description |
|---|---|
| `GET /api/library/jobs?scope=&languages=pl,en` | counters for the task boards, from the background scan (below), never probing files inside the request: `scope_label` (localized, null for the whole machine), `files` and `size` (videos in the scope), `checked_at` (when the scan last finished, null before the first one), `pending` (videos not scanned yet: new ones and files of unfinished torrents), `jobs` = `{kind: {"applicable", "count", "size", "saving", "eta" (seconds of work, null while the encoder speed is unknown), "why" (localized, when not applicable or nothing to do), "pending", "options"}}`. `options` per kind: `free` none (not applicable in a scope; `count` = disks under 15% free); `dupes` `same_files` `{groups, saving}`, `same_title` `{titles, saving}`, `seeding`; `audio_fix` `checked`, `total`, `examples` (up to 3: `name`, `codec`, `errors`); `modernize` `examples` (`name`, `format`, `size`), `eta_gpu`, `eta_cpu`; `downscale` `by_target` (`"1080"`/`"720"`/`"480"`/`"360"`: `count`, `saving`, `eta`; the top-level numbers are the 1080 target) and `folders` (`root`, `path`, `name`, `mix` like "4K 2 · 1080p 12", `saving_by_target`); `compress` `by_quality` (`transparent`/`balanced`/`saving`: `count`, `saving`, `eta`; top level = balanced, default filters) and `need_free`; `subs_get` `no_subs`, `no_subs_in_lang`, `have`, `per_day` (20), `languages`; `subs` `unchecked`, `watched_now`, `all`, `fitted_before`; `tags` (titles the catalog didn't recognise) `recognised`, `total`; `trash` (not applicable in a scope; `count` files, `size` bytes in the trash) `days` |
| `GET /api/config/jobs`, `POST /api/config/jobs` | `{"audio_fix_auto": bool, "subs_auto": "pl,en"\|""}`: new files found by the background scan get a "Fix sound (new files)" task when their sound is damaged, and a "Download subtitles (new files)" task for the missing languages. `POST` takes either field and returns both |

New plan kinds and parameters for `POST /api/library/plan` (each also takes `scope`; the old forms of `free`,
`modernize`, `dupes`, `subs_get` and `subs` are used when none of the new parameters below is present, and the old
kinds `shrink`, `tidy`, `trash` and `move` take a `scope` too, which filters their items). Every result carries
`scope_label` (in the language of the request); a task made from it keeps the label and `/api/tasks` returns
`scope_label` (string or null) and `kind`.

- `{"kind": "audio_fix", "quality": "standard"|"high"}`: videos whose MP3/MP2/AC3 sound has damaged frames; only those
  tracks become AAC (standard: 128k stereo / 256k 5.1, high: 192k / 384k), the picture is copied. The result has the
  shape of a `shrink` plan (`encoders`, `est`, `saved`, `hours`) plus `options`; items have `audio_fix: true`,
  `broken`, `audio_codecs`. Files the background check hasn't reached are checked while planning.
- `{"kind": "modernize", "encoder"?, "audio": "keep_if_ok"|"always_aac"}`: old picture formats (DivX 3, WMV,
  RealVideo, Flash, Theora, MPEG-4 ASP…) re-encoded to H.264 at the same resolution (so every phone and TV plays them),
  as `.mkv`; `keep_if_ok` copies sound phones play and converts the rest to AAC, `always_aac` converts all of it.
- `{"kind": "downscale", "target": 1080|720|480|360, "encoder"?, "remember"?: bool}`: H.265 at the target height,
  never enlarging; files already at or under it are left out. `remember` saves the matching profile (`good`,
  `compact`, `tiny`, `mini`) for the folders in `scope.roots`.
- `{"kind": "compress", "quality": "transparent"|"balanced"|"saving", "only_high_bitrate"?: true,
  "skip_hevc_av1"?: true, "skip_seeding"?: true, "encoder"?}`: H.265 at the same resolution (profile `same`); "high
  bitrate" is over 3 GB per hour. Files a torrent still seeds can't be re-encoded: with `skip_seeding: false` they are
  listed in `skipped`. Old formats are left to `modernize`.
- `{"kind": "dupes", "what": "same_files"|"same_title", "keep": "folder"|"quality"|"space"|"manual"}`: `same_files` are
  identical files (sampled hashes, hard links not counted), `same_title` the files of one catalog title (one film,
  one episode) in several copies. The result is kind `dupes` with `groups` (`files` carry `keep: true` on the copy that
  stays: a copy a torrent still seeds first, then the one in a folder whose tag fits the title for `folder`, the best
  picture for `quality`, the one on the disk with the most free room for `space`; none for `manual`) and `items` = the other copies, with op `trash`.
- `{"kind": "free", "root", "amount"?: bytes, "to_percent"?: 15, "order": "watched_oldest"|"largest",
  "skip_kept"?: true}`: deletes whole titles on the disk of `root` to the trash (not a move) until `amount` bytes or
  `to_percent` % free. `watched_oldest` takes only titles streamed from this server, the longest ago first; `skip_kept`
  leaves out titles streamed in the last 14 days (`skipped`, reason "being watched"). The space only comes back when
  the trash is emptied (`warnings`, `trash_days`). Not available in a scope (400).
- `{"kind": "subs_get", "languages": "pl,en", "which": "no_subs"|"no_subs_in_lang"|"new_only", "order": "recent"|"alpha"}`:
  `no_subs` = videos with no subtitles at all, `no_subs_in_lang` = missing one of the languages; `recent` puts the
  recently added and streamed first. `new_only` stores `subs_auto` (as `POST /api/config/jobs`) and returns a result
  with no items.
- `{"kind": "subs", "which": "unchecked"|"watched_now"|"all", "sub_to_srt"?: true, "mark_bad"?: false,
  "queue_bad"?: false}`: `watched_now` = unchecked subtitles in folders streamed in the last 14 days; `sub_to_srt:
  false` leaves out `.sub` files; `mark_bad` moves a default subtitle that doesn't fit to `Subtitles/` (the item's
  `result` says `aside`); `queue_bad` queues a "Download subtitles that didn't fit" task for those languages when the
  task finishes.

Played titles: the agent remembers when each video was last streamed from it (`played.json`, a `GET` of `/lib/…`;
`HEAD` probes don't count), which is what "watched" means above.

### Folder layout, balance, For Chromecast, pause and queue order (agent 1.17+)

`features.jobs` adds `layout`, `balance` and `cast_ready`; `features.task_queue: true` means the task routes below
exist. Apps show these screens only when the agent lists them.

New plan kinds for `POST /api/library/plan`:

- `{"kind": "layout", "levels"?: ["kind", "age", "category", "genre", "collection", "decade"], "ages"?: {"0": "kids",
  "7": "kids", "12": "teens", "16": "films", "18": "films"}, "keep"?: [{"root", "path"}], "keep_unsure"?: true,
  "seeding"?: "move"|"skip", "remember"?: false}` (A2): moves whole titles into folders by catalog tags, inside the
  same disk (into the library folder there whose tag fits the kind: films, series, kids). `levels` in order are the
  folder levels: `kind` picks the library folder, `age` puts films under `Kids/` / `Teens/` (by the `ages` mapping,
  `films` = no folder), `category` the grouping folder (`Anime/`), `genre` the first catalog genre, `collection` the
  film series (`Blender Open Movies/`), `decade` `1960–1969/`. Without `levels`/`ages` the saved ones are used
  (`layout_levels`, `layout_ages` in `/api/config/jobs`; `remember: true` saves the given ones). `keep` = the folders
  arranged by hand that stay as they are: given once, it is saved as `layout_keep` (confirmed); never confirmed, every
  detected hand folder is kept. `keep_unsure: false` lists titles without a sure catalog match as `unsure` items (the
  app leaves them unticked). `seeding: "move"` moves a seeding torrent through its client, whole (a torrent holding
  several titles moves only when they all belong in the same folder, else it is skipped with reason "together");
  `skip` leaves seeding torrents alone. A title another queued or running task still works on is skipped ("busy").
  Agent 1.17.1+: when one disk has several folders whose tag fits, a folder fits a title only if the title has all of
  its tags, and the most tags win (`bajki` + `pl` beats `bajki` for a cartoon with Polish sound). Known tags: kinds and
  their synonyms, age groups, `anime`, `muzyka`/`music` and sound languages (`pl`, `en`, `de`, `fr`, `es`, `it`, `ru`,
  `ja`, `uk`, `cs`; from the facts scan, or set by hand); other folder tags don't count. An unknown sound language or a
  tie leaves the title in its folder. The item's `why` names the winning tags, e.g. "(bajki + pl)".
  Also 1.17.1+: the `kids` age group takes only titles for children, not every low age rating: Wikidata says
  children's film (P136) or intended public children (P2360), or the title is animated without dark genres (with
  "Family" or an age rating; unrated animation may be adult anime). A live-action film rated 6 stays with the films,
  a cartoon rated 12 or unrated goes to the kids. A title already in a kids folder stays a kids title (the catalog's
  match may be wrong), and "Family" alone (live-action fairy tales) stays where it is.
  Result: `items` (op `move` with `to_root`, `to_name`, `to_sub`, `torrent`, `titles` = titles in that torrent,
  `unsure`, `why` = the destination, `prune` = empty folders left behind are removed, `same_disk: true`), `levels`,
  `ages`, `confirmed`, `hand_folders`, `stats` (`unsure`, `seeding`, `kept`), `skipped`, `eta`. A task with only
  same-disk moves is quick: it runs between the files of a running encode instead of waiting.
- `{"kind": "balance", "goal": "even"|"kind_per_disk"|"fill_newer", "rules"?: {"skip_watching": true, "seeding":
  true, "backup": false}}` (A3), the whole machine only (400 with a scope; an error with one disk): whole titles move
  to the folder with the same tag on another disk, same subfolder. `even` evens out the free share, `kind_per_disk`
  gathers the series on the disk holding most of them and moves films and the rest to the others, `fill_newer` fills the largest disk first (leaving
  10% free). A series always moves whole; no move leaves a disk under 5% free. `skip_watching` leaves what was
  streamed in the last 14 days or is watched now; `seeding: false` leaves seeding torrents (true: moved whole by the
  client, which keeps seeding); `backup: true` never moves a title of a replicated folder onto its replica's disk.
  Result: `items` (op `move`, `why` = destination), `disks` (`name`, `total`, `free`, `free_after`, `pct`,
  `pct_after`), `goal`, `rules`, `skipped`, `eta` (copy time).
- `{"kind": "cast_ready", "add_aac"?: true, "subs_to_srt"?: true, "avi_to_mkv"?: true}` (B5): videos whose picture a
  Chromecast plays (H.264 8-bit up to 1080p, HEVC 8-bit) but whose sound it doesn't (DTS, AC3, E-AC3, TrueHD…), with
  text subtitle tracks, or in AVI. Running an item writes the text subtitle tracks next to the video as `.<lang>.srt`
  and remuxes it to `.mkv` with an AAC stereo track (192k) first and default, the original tracks kept; the picture is
  copied, never re-encoded. The original goes to the trash. A file a torrent still seeds only gets its subtitles
  written next to it. Items: `why` (localized reasons), `est` (size after), `add_aac`, `aac_from`, `aac_lang`,
  `audio_codec`, `subs` `[{n, lang}]`, `avi`, `seeding`, `title`, `series`; result: `est`, `saved` (negative: the
  files grow), `eta`, `by_title`, `warnings` (files whose subtitle codecs weren't probed yet).

Counters in `GET /api/library/jobs` (also `?levels=kind,age` for the layout counter with other levels than the saved
ones): `layout` `count` titles to move, `options` `levels`, `ages`, `confirmed`, `hand_folders` (`root`, `root_id`,
`root_name`, `path`, `name`, `titles`, `size`, `keep`), `unsure`, `seeding`, `kept`, `tree` (`[{path, titles}]`, the
destination folders, e.g. `Films/Kids`); `balance` (not applicable in a scope or with one disk; top-level numbers for
`even`) `options` `disks` (as in the result, now) and `by_goal` `{goal: {count, size, eta, disks}}`; `cast_ready`
`count` files, `saving` (negative: growth), `eta`, `pending` (files whose subtitle codecs aren't known yet),
`options` `by_title` (top 3: `title`, `series`, `files`, `why`, `size`), `more_files`, `by_reason` `{aac, subs, avi}`,
`growth`. `GET /api/config/jobs` also returns `layout_levels`, `layout_ages` and `layout_keep` (`[{root, path}]`, null
= never confirmed); `POST` takes them (`layout_keep: null` forgets the confirmation).

Task routes (`features.task_queue`):

| method and path | description |
|---|---|
| `POST /api/tasks/<id>/pause` | a person pauses a queued or running task (`held`): a running one stops at the next file boundary, or its detached encode is suspended (SIGSTOP; on Windows it finishes the file); it goes back to `queued` and keeps its place. Survives an agent restart → the task |
| `POST /api/tasks/<id>/resume` | continues it (a suspended encode is resumed and re-attached) → the task |
| `POST /api/tasks/<id>/move` | `{"to": "up"\|"down"\|"top"}`: a queued task in the queue → `{"queue": [ids]}` |
| `POST /api/tasks/order` | `{"ids": [...]}`: the queued tasks in this order (others keep theirs after them) → `{"queue": [ids]}` |
| `POST /api/tasks/<id>/retry` | a new task with the failed items of a finished or cancelled one (after emptying the trash) → the new task |

`/api/tasks` adds per task: `held`, `paused` (true also when held), `quick`, `queue` (1-based place of a queued task),
`eta` (seconds left of a running task, without paused time, or of work for a queued one; null when unknown),
`estimate` (localized, for a queued task: "Quick: starts in a break between files", the OpenSubtitles daily limit);
`error_groups` items of a finished task that ran out of space carry `fix: "empty_trash_retry"`, `trash` (bytes in the
trash) and `disk`. `POST /api/tasks/<id>/remove` also takes a queued task (it leaves the queue).

The catalog (`/api/catalog/*` entries) adds `series` (up to two film-series Wikidata ids, P179) and their labels;
entries cached before are fetched again once. Folders named like the layout's own (`Films/`, `Kids/`, `Teens/`, a
decade) are looked into: their subfolders are titles, as at the top of a library folder.

## Catalog: films instead of folders (agent 1.8+, `features.catalog`)

The agent walks the library in the background and keeps every film and series as a title: identification (local IMDb
index), languages of the audio tracks and subtitles (ffprobe once per file + subtitle files next to it) and details from
Wikidata by IMDb id (cast in billing order with roles, directors, main subjects, age rating, Polish title). See
`mp_catalog.py`. Lists are in the request's language (`Accept-Language`) where it matters (topics, reasons).

| request | answer |
|---|---|
| `GET /api/catalog?since=<version>` | `{"version", "same": true}` when nothing changed since `version`; otherwise `version`, `scanning`, `done`, `total` (walk progress), `last` (time of the last walk), `items`, `people` (`{Wikidata id: name}`), `topics` (`{Wikidata id: label}`). Item: `id` (stable key), `root`, `root_name`, `path` (main video, relative to the folder; a series: its first episode), `dir` (the title's folder or null), `title` (original), `title_pl` (only when the film has Polish audio or subtitles), `year`, `kind` (`movie`/`series`), `imdb`, `genres` (IMDb, English names), `rating` (IMDb, 0–10), `votes`, `minutes`, `height`, `audio`, `subs` (2-letter languages; `?` = a subtitle file of unknown language), `size`, `added` (time), `kids`, `episodes`, `age` (minimum age from FSK/MPAA, 0 for kids' folders, null = unknown), `cast`, `roles` (same order as `cast`), `directors`, `topics` (Wikidata ids). Newest first |
| `GET /api/catalog?since=<cursor>` | agent 1.18+ (`features.catalog_delta`): every answer also has `cursor` (`"<epoch>.<version>"`). Sent back as `since`: `{"version", "cursor", "same": true}` when nothing changed; `delta: true` with `since` (the version it starts from), `items` (only the items changed or new since then, whole), `removed` (ids of items gone since), `people` and `topics` (names for those items only) and the usual `version`, `cursor`, `scanning`, `done`, `total`, `last`. The phone replaces items by `id`, drops `removed`, merges the names and sorts newest first. The whole catalog (no `delta`) comes instead when the cursor's epoch is another one (the catalog file was rebuilt, names were relabelled, the IMDb index was rebuilt, the asking device's folders, their tags, visibility or exclusions changed, another `Accept-Language`), when the cursor is older than the tombstones kept (5000 removals) or newer than the catalog, when the catalog has 100 items or fewer, or when more than half of it changed. A bare version (`since=<version>`, apps before 1.18) works as before |
| `GET /api/catalog/title?imdb=tt…` | a title, also one that is not on the disks: the IMDb index row (`title`, `year`, `kind`, `genres`, `rating`, `votes`, `minutes`) + `have`, `copies` (catalog items with this id), `cast` (`[id, name, role]`), `directors` (`[id, name]`), `topics` (`[id, label]`), `age`. Agent 1.17+ (`features.catalog_search`): a title that is not on the disks also has `about` (the lead of its Wikipedia article, Polish for `Accept-Language: pl` when there is one, else English; up to 1500 characters) and `about_source` (`"Wikipedia"`), when Wikidata links an article; fetched when the page is opened, cached for a day, left out offline |
| `GET /api/catalog/search?q=<text>&limit=20` | agent 1.17+ (`features.catalog_search`): titles from the local IMDb index (films and series with 300+ votes) whose original or primary title starts with `q` (case, accents, punctuation and the words the/a/an/and ignored; under 3 characters only whole titles), an exact title first, then the most voted; a trailing year ("Metropolis 1927") ranks that year first. `items: [{imdb, title, year, end, kind, genres, rating, votes, minutes, have, id?}]` (`have`: a title this device sees in the catalog has the same IMDb id, `id` is its catalog key), `index` (false while there is no index yet). `limit` 1–50. No network: one indexed lookup |
| `GET /api/catalog/episodes?imdb=tt…` | agent 1.16+ (`features.episode_lists`): every season and episode of a series, the ones on this server marked. `seasons: [{season, episodes: [{season, episode, title, imdb, aired, year, have, state, root, path}], have, missing, upcoming, total}]`, the same four counts for the whole series, `other` (own videos that match no episode: `[{root, path}]`), `source` (`wikidata`, `imdb` or null), `running`, `pending` (an IMDb datasets pass is still queued). `state`: `have`, `missing` or `upcoming`. Sources: Wikidata first, IMDb datasets (a niced weekly pass) when Wikidata is incomplete; no API keys. The first request for a series waits for Wikidata (up to 30 s); later ones answer from the cache. Without online lookups (`ident_online: false`) only the own files, all in `other` |
| `GET /api/catalog/person?id=Q…` | `id`, `name`, `role` (`director` when Wikidata lists more films they directed than acted in, otherwise `actor`), `films` (catalog items with `role` and `as_director`), `missing` (up to 20 best known films with this person that are not on the disks: `imdb`, `title`, `year`, `genres`, `rating`, `votes`, `minutes`, `have: false`) |
| `GET /api/catalog/next?imdb=tt…` | "what next": `items` (up to 24), titles from the collection first (`have: true` with `id`, `root`, `path`, `audio`, `subs`, `title_pl`?) then ones that are not on the disks (`have: false`); each with `reason` (`actor`/`topic`/`genre`) and `why` (text: "też Cary Grant", "Mystery, Romance"). Wikidata queries are cached for a day; the first answer may take ~10 s |
| `GET /api/catalog/suggest?imdb=tt…&limit=20` | agent 1.18+ (`features.suggest_missing`): Discover's "Because you watched <title>: not on your disks" row. Popular titles (5000+ IMDb votes) from the local IMDb index, of the same kind (film or series) as `imdb`, that share its directors (first two), first billed actors (four), genres (pairs of its specific genres, within ±15 years) or era, and that no library folder has (any folder, not only the ones the device sees). Ranked by a score (a shared director 4, each shared actor 2.5, each shared genre 1.5, Drama/Comedy/Romance 0.5, within 10 years 1, plus log10 of the votes), at most 4 for one person or for genres alone before the rest; a kids' title gets only animation and family titles. `items: [{imdb, title, year, kind, genres, rating, votes, minutes, have: false, reason, why}]` (`reason`: `director`, `actor` or `genre`; `why`: "directed by Fritz Lang", "also …" or the shared genres, Polish for `Accept-Language: pl`), `source` (`{imdb, title, year, kind}`), `index` (false while there is no index yet: `items` empty). `limit` 1–40. Candidates are computed once per watched title and kept for a day (5 minutes when a Wikidata query failed); the first answer for a title may take 2–30 s (Wikidata people lookups, shared with `/api/catalog/next` and `/api/catalog/person`), later ones are instant and only drop what the library has meanwhile. Without online lookups (`ident_online: false`) genres and era only. 400 for a bad id |
| `GET /api/wishlist` | `items`: the shared "want to watch" list: `imdb`, `title`, `year`, `genres`, `why`, `by` (device name), `at`, `have` (+ `id`, `root`, `path`, `where` when the film is on a disk now) |
| `POST /api/wishlist` | `{"imdb", "title", "year"?, "genres"?, "why"?}` adds (no duplicates) → the list |
| `POST /api/wishlist/remove` | `{"imdb"}` → the list |
| `GET /api/config/search` | `engines`: `[{"name", "url"}]` set by the user, empty by default. The app shows "Search" for a film that is not on the disks only when there is at least one, and opens `url` in the browser with `{title}`, `{year}` and `{imdb}` filled in. The agent never queries them and the project ships none |
| `POST /api/config/search` | `{"engines": [{"name", "url", "kind", "open"}]}` (up to 10; `url` is http(s) with `{q}`, `{title}` or `{imdb}`; agent 1.16+ keeps `kind`: `torrents` (default), `video` or `subtitles`, and `open`: `browser` (default) or `app`, the app's own browser that sends magnets to the server) → the saved list; `features.search_engines` is their number |

## Watch state from phones (agent 1.18+, `features.watch_sync`)

A phone keeps positions, "watched" marks and subtitle choices itself and works without the computer. At a sync point
(the server answers again, the app comes back on screen after a while, the Library refreshes its catalog) it sends what
changed since its last sync and gets back what the agent has newer for this device. See `mp_watch.py`.

| request | answer |
|---|---|
| `POST /api/watch/sync` | body `{"now": phone epoch ms, "since": the last answer's seq (0 the first time), "items": [{"root", "path", "pos"? ms, "pos_at"? ms, "watched_at"? ms, "sub"? name, "sub_at"? ms}]}` (library videos: `root` = folder number, `path` relative to it; up to 300 per request). Times are the phone's: the agent moves them onto its own clock with `now`. Per device (the token's) and per video the newer change wins: `pos` with `pos_at`, `watched_at`. `sub` (a version picked on a downloaded copy while offline, a name from `/api/subs/list`) becomes its language's default like `POST /api/subs/default`, unless the video's subtitles changed on the server after `sub_at` (a choice made there, a file written or renamed) or the version is gone. Positions and "watched" also count as "streamed" for the disk tasks (`played.json`, at the time watched). → `{"seq", "items": [{"root", "path", "pos", "pos_at", "watched_at"}], "subs_applied": [{"root", "path", "sub", "name"}], "subs_kept": [{"root", "path", "sub"}]}`: `items` are this device's entries changed after `since` (except the ones in this request), times on the phone's clock; `subs_applied` = choices made the default (`name` = the version's name now), `subs_kept` = choices turned down (the server's subtitles stay; the phone refreshes its copy). 403 without a device token. State in `watch.json`; moves made by tasks re-key it |

## Files sent from phones (agent 1.8+)

Every finished upload is remembered with the phone it came from (`uploads.json`), so the Downloads screen can show what
landed on the server. Video files in the upload folder without an entry (sent earlier) are listed too.

| request | answer |
|---|---|
| `GET /api/uploads` | `new` (how many wait for a decision), `items` (newest first): `id`, `name`, `device` (null for older files), `size`, `at`, `state` (`new`/`moving`/`moved`/`kept`/`gone`), `to` (folder name after a move), `moved_at`, `error`; a `new` one also has `guess` (as in `/api/library/guess`), `targets` (with `here`: the folder it lies in now) and `pick` (suggested target index or null) |
| `POST /api/uploads/move` | `{"id", "root", "sub"?, "learn"?, "guess"?}` moves the file and its subtitles into the library folder in the background (another disk: a copy, `state: "moving"` meanwhile); `learn` remembers the folder for this title like a magnet does |
| `POST /api/uploads/keep` | `{"id"}`: leave it where it is (no longer "new") |

## MCP for an AI assistant (agent 1.6+, `features.mcp`)

| method and path | description |
|---|---|
| `POST /mcp` | MCP over Streamable HTTP (JSON responses only), `Authorization: Bearer <device token>`; without it `401` with `WWW-Authenticate: Bearer resource_metadata=…`. Tools in `server/mp_mcp.py`; a guest device gets only the read tools |
| tool `set_title_tags` | `{"imdb": "tt…", "tags": ["pl", …]}` (agent 1.17.1+, household member): hand tags of a title, saved as `title_tags` in the config; they count for the folder layout's choice of folder (a language tag stands for the title's sound) |
| `GET /.well-known/oauth-protected-resource`, `GET /.well-known/oauth-authorization-server` | OAuth metadata (RFC 9728, RFC 8414) |
| `POST /oauth/register` | dynamic client registration; redirect URIs: loopback `http` or `https` |
| `GET /oauth/authorize` | page in the browser: files an access request and waits for the phone; then redirects with a one-time code (2 min) |
| `POST /oauth/token` | `grant_type=authorization_code`, `code`, `code_verifier` (PKCE S256) → `{"access_token", "token_type": "Bearer"}`: an ordinary device token |

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
| `POST /api/player/open` | `ref` + optionally `start` (seconds); or a video from another server: `{"url", "subs": [url…], "title", "start"?}`. Agent 1.13+ (`features.player_sub_lang`): `sub_lang`, the phone's last subtitle choice: a language (the first subtitle track in it is selected) or `"off"`. Agent 1.13.3+ (`features.player_audio`): `audio`, the phone's remembered audio choice `"kind|lang"` (`lector\|pl`, `original\|`, `\|en`; kind from the track title: lector, dubbing, original, description, commentary); the matching track is selected once the file is loaded, as the phone's player does; agent 1.14.2+: a library video gets its recognised name as mpv's title (`player/status` `title`: "Series · S2 E3", "Film (1968)") instead of the file name |
| `GET /api/player/status` | `running`, `title`, `path`, `pos`, `duration`, `paused`, `volume`, `muted`, `fullscreen`, `sub`, `audio`, `tracks[]` (`id`, `type`, `title`, `lang`, `codec`, `selected`, `external`, `default`, `forced`, `demux-channel-count`, `demux-w`, `demux-h`) |
| `POST /api/player/cmd` | `{"cmd", "value"?}`, commands below |

Commands:
- playback: `toggle_pause`, `pause`, `play`, `seek` (s, relative), `seek_to` (s), `stop`;
- picture and sound: `fullscreen` (toggles without `value`), `volume`, `volume_rel`, `mute`;
- tracks: `track` (`{"type": "video|audio|sub", "id": N|"no"}`), `sub_cycle`, `audio_cycle`, `sub_delay` (s).

## Subtitles

| method and path | description |
|---|---|
| `GET /api/subs/list?<ref>` | subtitles next to the video: `name`, `lang`, `ext` (a text `.sub` is listed as `srt`: `/sub` serves it converted); agent 1.9+: `fit` `{"verdict": "good"/"unsure"/"bad", "z"}` when the file was assessed against the film; agent 1.12+ (`features.subs_versions`): `default` (true = next to the video, the language's version in use; false = another version in `Subtitles/` or a torrent's `Subs/`, `name` is then a relative path) and `source` `{"provider", "id", "release"}` for files the agent downloaded; agent 1.18+: `mtime` (epoch s, when the file was last written: a downloaded copy on a phone compares it at a sync point) |
| `POST /api/subs/default` | agent 1.12+: `ref` + `name` of another version → it becomes its language's default (`<name>.<lang>.srt`), the current default moves to `Subtitles/`; → `{"name"}`. 409 when the current default belongs to a seeding torrent |
| `GET /sub?<ref>&name=[&vtt=1]` | the subtitle file. A `.srt` always comes as UTF-8 SubRip, also when the file is really MicroDVD, MPL2 or TMPlayer (often cp1250). With `vtt=1` the agent converts it to WebVTT (for `<track>` in the browser) |
| `POST /api/subs/search` | `ref` + `languages: "en,pl"`, optionally `title` and `year` (manual correction) → `query`, `title`, `year`, `providers`, `results[]` (`id`, `provider`, `lang`, `release`, `score`, `hash_match`, `hi`, `title_match`), `hidden`. With `"async": true` it returns `{"job"}` right away. Agent 1.17.2+: without `title`, when neither the file's hash nor its name finds subtitles of this title, one more round (`round: 2` in the job) asks by the catalog's titles (IMDb's, English and Polish Wikidata labels), year and IMDb id; a result matched by IMDb id counts as `title_match`. The task "download missing subtitles" uses it too. Agent 1.17.3+: each result also has `downloads`, `machine` (machine or AI translation), `trusted` (trusted uploader), `rank` (the uploader's rank, when given), `fps`, `fps_off` (the subtitles declare a frame rate other than the video's) and `imdb` (the film or series the provider files them under); results are sorted by language, then not machine-translated, hash match, same frame rate, score, trusted, downloads (`mp_subs.rank_key`; 1.17.5+: among hash matches a provider without a download quota, e.g. NapiProjekt, before OpenSubtitles), and the automatic download skips machine translations while anything else is left |
| `POST /api/subs/job/stop` | `{"id"}`: "That's enough", don't wait for slow providers and skip the second round |
| `GET /api/subs/job?id=` | progress: `phase` (`file`/`search`/`match`/`done`), `round` (2 = title from `[brackets]`), `query`, `matched`/`total`, `providers` (`state`: `pending`/`running`/`done`/`skip`/`error`, `count`, `error`, `secs`), `elapsed`, `done`, `result` or `error` |
| `POST /api/subs/download` | `ref` + `id`, `provider`, `languages` (and the same `title`/`year` as for the search) → `{"name", "provider", "release"}`; agent 1.12+: the new file becomes the default and the previous default of that language moves to `Subtitles/` (nothing is overwritten; downloading the same subtitles again reuses that file). Agent 1.12.1+: MPL2, MicroDVD and TMPlayer subtitles (NapiProjekt gives MPL2) are saved as a real SubRip `.srt`. If the video is playing in mpv, the subtitles are turned on right away. 422 when the file is empty (all cues at zero time or blank). Agent 1.9+ (`features.subs_fit`): the file is then fitted to the film's speech; with `"fit": "wait"` the response carries `fit` `{"verdict", "z", "ratio", "offset", "changed", "name", "locked"}` (takes ~30 s the first time for a film; `name` may change when a `.sub` became `.srt`), otherwise `fit: {"verdict": "pending"}` and it happens in the background. Agent 1.12.1+ (`features.subs_download_job`): the file is written and fitted in a temporary folder and only then placed next to the video, so a player never sees it unfitted; with `"async": true` the response is `{"job"}` at once and `GET /api/subs/job?id=` shows `phase` `download` → `fit` → `done` with the same `result` (the app shows "Downloading…", "Fitting…") |
| `POST /api/subs/fit` | agent 1.9+: `ref` + `name` of a subtitle file next to the video → the same `fit` object (agent 1.12.1+: `by` = `embedded` when the video's own subtitle track was the reference, `speech` otherwise); a file of a torrent in the client is only assessed (`locked`) |
| `POST /api/subs/shift` | agent 1.12.1+ (`features.subs_shift`): `ref` + `name` + `by` (seconds, + later, - earlier) → the file is rewritten (the original kept the first time), mpv playing the video reloads it; → `{"name", "manual"}` (total shift by hand, also in `subs/list` `fit.manual`). 409 for a seeding torrent's file |
| `GET /api/config/app_updates` | `{"mode": "auto"\|"manual", "last_check", "last_error", "published": {"version_code", "version_name"}}`: the phone app from GitHub releases |
| `POST /api/config/app_updates` | `{"mode": "auto"\|"manual"}`; switching to `auto` checks right away |
| `GET /api/config/shrink` | `{"encoder": "auto"\|"cpu"\|"gpu", "auto": "cpu"\|"gpu" (what auto resolves to), "while_watching": "continue"\|"pause", "detecting", "encoders": [{"id", "label", "note", "bframes"}]}`: the encoder for shrinking and what happens while somebody watches (agent 1.7+, also in `features.shrink_encoder` / `shrink_while_watching`) |
| `POST /api/config/shrink` | `{"encoder"?, "while_watching"?}` → the same as GET |
| `GET /api/config/agent_updates` | `{"mode", "supported", "version", "available", "state": "idle"\|"downloading"\|"waiting"\|"installing"\|"failed"\|"error", "waiting_for": "stream"\|"conversion", "last_check", "last_error", "last_result": {"version", "ok", "error", "time"}}`: the agent's own updates (agent 1.6+) |
| `POST /api/config/agent_updates` | `{"mode": "auto"\|"manual"}`; switching to `auto` checks right away |
| `POST /api/agent/update` | check now and install a newer agent at the next quiet moment (also a version that was rolled back before); 409 where unsupported |
| `GET /api/config/opensubtitles` | `api_key` (whether set), `username`, `password` (whether set) |
| `POST /api/config/opensubtitles` | `{"api_key"?, "username"?, "password"?}`; empty fields don't overwrite saved ones |
