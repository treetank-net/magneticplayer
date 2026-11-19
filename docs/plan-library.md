# Plan: a tagged library, tidying up disks, covers

Status (2026-11-19): stage 1 (tags, visibility, exclusions) is in agent 1.4.0 and in the app, without a screen during
server setup (for now a pencil next to the folder in the Library). Stage 2 (magnet → folder) is in agent 1.5.0 and in
the app: recognition, downloading straight into the folder with the tag, "Change" with moving and remembering the
choice. Missing: re-recognizing a magnet without a name (`dn=`) once the metadata has been downloaded. Stages 3–7 are
in agent 1.6.0 (with its self-update): background tasks with moving and replication, disks, tidy-up, duplicates,
covers, storage profiles with a trash, the MCP server; the app has screens for them (Library → "Disks and tidy-up", folder settings, plan preview, task notifications). Not done
yet: renaming to `Title (year)/…`, TMDB posters, Windows self-update. Stage 8 (browsing as films, files from phones) is in agent 1.8.0. The API still points to a folder by index;
`id` is in the configuration for replication.

## Today

- Library folders are a list of `{name, path}` in `config.json` (`library`). On first start the agent adds the
  torrent client's download folders and `~/Wideo`/`~/Videos`/`~/Filmy`; after that they are added in the app
  (Library → "Add folder") or on the web page.
- Every device sees all folders with all their contents; so does a guest (only without adding and removing). The
  only hiding: a name starting with a dot.
- A folder is referenced by its **index** in the list (`root=2`), so removing a folder shifts the others.
- A torrent downloads wherever Deluge's default is.

## Decisions

1. **Tags on folders** (`filmy` = movies, `seriale` = series, `bajki` = kids, `kopia` = backup, plus custom ones; the
   tag values are Polish and stored as is). A tag says what should go there and is a filter in the library. `kopia`
   doesn't show in the library.
2. **Visibility per folder:** everyone / owners only / selected devices, plus a list of subfolders excluded from the
   library. A hidden folder is also unavailable via the stream, a ticket, Chromecast and subtitles, not just
   invisible in the list.
3. **Server setup:** after pairing (and later in the server details) the app shows disks and folders; the user picks
   which ones to attach, with which tags and who sees them. `~/Downloads` is no longer in the library by default.
4. **Load balancing:** several folders with the same tag → a new file goes where there is the most free space.
   Exception: further episodes of a series go where that series already is.
5. **Replication:** a folder option "also copy to …". After the download the agent copies the file to a second disk
   in the background. The copy isn't a duplicate and doesn't show in the library.
6. **Choice on a magnet on the phone:** the app sends the name from the magnet (`dn=`), the agent recognizes it with
   `guessit` (already in the venv as a subliminal dependency) and suggests a folder. One screen: "Movie: Title (year)
   → Movies on TOSHIBA (1.2 TB free)", with the option to change it. Deluge downloads straight there
   (`download_location`), so nothing has to be moved afterwards. A magnet without a name: ask once the client knows
   the metadata.
7. **Tidying up existing files:** a preview "will move X → Y", moving only after "OK". To start with, only moving to
   the right folder, no renaming (renaming to `Title (year)/…` as in Jellyfin — maybe later, for torrents via the
   client's `rename_files`).
8. **Torrent still in the client:** a file from a torrent that is still in Deluge/Transmission is moved by the client
   (`core.move_storage` / `torrent-set-location` with `move`), so it has the new path and keeps seeding. The whole
   torrent folder is moved.
9. **Few notifications:** one operation (tidying up, replication, moving) = one background task with progress in the
   app (like conversion) and at most one notification at the end ("Moved 14 files, 2 errors"). Replication after a
   download is silent; an error is collected in the folder's state, not in a separate notification.
10. **Duplicates and disk usage:** a disk view (free/used, how much is in the library), duplicates by size, then by a
    hash of the beginning and end of the file, and finally a full hash to be sure. Deletion only by hand.
11. **A shared library on the phone:** one list from all the servers and folders the device can see, with a tag
    filter.
12. **Covers as in Jellyfin/Plex:**
    - a file next to the video (`poster.jpg`, `folder.jpg`, `<name>.jpg`) takes precedence;
    - without one, a frame from the video (ffmpeg, ~10% into it), in the agent's cache, no accounts or network;
    - optionally a TMDB key (like the OpenSubtitles key): poster, description, year; requires the "data from TMDB"
      attribution.
13. **Tidying up "on request" as we did on 18.11:** the user asks, the agent walks the folders with `guessit` and
    proposes a structure (`Filmy/`, `Seriale/<title>/`, `Bajki/`, `Muzyka/`, `Inne/` per disk, i.e.
    Movies/Series/Kids/Music/Other, each as a library folder with a tag), with a list to approve. Done by hand on
    jmpc: 105 items, an undo log in `~/.config/magneticplayer/porzadki-2026-11-18.json`. Recognition needed manual
    corrections (e.g. a movie with a number in the title taken for an episode, anime and a cooking show without
    S01E01 taken for movies, comics and music), so a preview before moving is necessary. We expect the same from
    users with their own AI agent.
14. A torrent being downloaded doesn't rename files (protocol), but it downloads straight into the folder chosen by
    the rules (point 6), so the collection stays organized without moving.
15. **Recognition (`server/mp_ident.py`, `POST /api/library/guess`):** `guessit` alone isn't enough (it doesn't know
    the genre and mistakes numbers in titles for episodes). A magnet has only a name, a torrent a name and a file
    list, sometimes an `.nfo` with an IMDb id. So the agent keeps a small index built from the free IMDb dumps
    (datasets.imdbws.com, personal use): movies and series with ≥300 votes, genres as a bit mask, normalized titles;
    ~125k titles, 12 MB of SQLite, built in ~20 s. Order: IMDb from `.nfo` → `guessit` + index (year, kind,
    popularity) → contents (S01E01 episodes, music, shows/comics, a folder without videos stays where it is). Series
    only by episode markers, not by the number of files (a movie collection isn't a series). Kids: animation +
    `Family` with confidence; animation alone or `Family` alone with low confidence, because IMDb doesn't tell adult
    anime from children's cartoons. Test on the hand-organized jmpc collection: 87/92 matched, 61/62 at high
    confidence; the misses are Polish-only titles, adult anime, live-action remakes and an animated children's series
    without `Family`. That's why Wikidata fills in uncertain items (Polish titles, "children's film/series", FSK/MPAA
    age ratings: animation up to age 6 or live-action family from age 0 = kids): 90/92, the miss being an animated
    film rated FSK 12 that the user keeps under kids. Low confidence = the magnet dialog doesn't close by itself, and
    changing the folder ("Change") remembers the choice for that title.
16. **Storage profiles (lossy shrinking on request):** losslessly there's little to gain in video (on jmpc: samples
    0.1 GB, audio ~10% of the size, few DTS/extra tracks), so the real lever is re-encoding — but only where the user
    asks for it. Measured on jmpc (18.11): 222 GB of video in 250 files, mostly H.264 1080p at 4–5 Mb/s; the
    TOSHIBA disk is 96% full while My Passport has 1.1 TB free, so moving (stage 3) comes first.
    - A profile is set per folder (tag), per subfolder (a series) or per item (done: `profile` and `profiles` in the
      folder's config), e.g.:
      `original` (never touch, the default; always for `kopia`), `tidy` (lossless: drop samples, audio tracks in
      languages nobody here speaks, DTS/TrueHD → E-AC3/Opus), `good` (HEVC ~1080p, quality-targeted CRF),
      `compact` (HEVC 720p, e.g. for `bajki` or watched series), `tiny` (480p, for "keep it just in case").
    - Only files no longer in the torrent client (re-encoding would break seeding). A torrent still seeding shows
      "after seeding" instead.
    - Before starting: a preview with the estimated size after (from bitrate and profile) and the total saved, per
      folder; the user confirms. The estimate is checked against reality after the first few files.
    - Encoder: chosen per task in the preview (agent 1.7): x265 on the CPU at `nice 19` (better quality per byte)
      or the graphics card (NVENC/VAAPI), with saving and hours of work shown for each. `auto` takes the card only
      when it does HEVC B-frames (RTX 20xx and newer); on jmpc a GTX 970 (no B-frames, 8-bit only) stays on the CPU
      by default. Encoding goes on while somebody watches; see architecture.md for how it yields to the stream.
      AV1 (SVT-AV1) where the household's players handle it.
    - Old formats (agent 1.8.2): DivX 3, WMV, RealVideo and the like are re-encoded even when it saves nothing, so a
      collection does not rot as players drop old decoders; "Old formats" in Disks finds them across the library.
    - Compatibility: the agent knows the household's players (Chromecast profiles, the DLNA TV, phones). If one of
      them can't play the target codec (e.g. Chromecast gen 1–3 and HEVC), the preview says so and offers H.264 at
      a lower resolution instead, or relies on "Prepare" when watching.
    - Safety: the new file is verified (duration, decodes from start to end, size really smaller) before it
      replaces the original; the original goes to a per-disk trash for N days (configurable, 0 = delete right
      away). Subtitles next to the file stay; embedded subtitles and chapters are kept.
    - One background task per request, with progress in the app and one notification at the end ("Shrunk 14
      files, saved 38 GB, 1 error").
17. **MCP server in the agent (organising by an AI assistant):** the user's own assistant (e.g. Claude Code on a
    computer in the LAN) connects to `http://<agent>:8765/mcp` and organises the library with the same tools the
    app uses. Access is approved on the owner's phone, like the existing access requests: the assistant asks, the
    phone shows "Claude on <host> wants to manage the library", the owner allows it, the assistant gets its own
    device token (revocable in the device list). OAuth through the MCP authorization flow if clients accept it over
    plain HTTP in the LAN (OAuth 2.1 expects HTTPS outside localhost; to be checked), otherwise the token is passed
    as a header.
    - Read tools (only stage 1 needed): list folders and tags, browse, search, file details (ffprobe),
      `identify` (guess), disk usage.
    - Write tools: set tags/visibility/exclusions (stage 1), propose a tidy-up plan and apply it as a background
      task (stage 3), set storage profiles (stage 6). Nothing moves or gets re-encoded without a preview the
      owner confirms (in the assistant's session or on the phone).
    - Scope per token: `read` or `organise`; guests never.
18. Photos and documents (rules like `year/month/name.pdf`) — outside the app (Immich, PhotoPrism, Paperless-ngx).

## Folder model (config.json)

```json
{"id": "k7f3", "name": "Filmy (TOSHIBA)", "path": "/media/jm/TOSHIBA EXT/Filmy",
 "tags": ["filmy"], "visible": "all", "exclude": ["prywatne"], "replicate_to": "p2c1"}
```

- `id` is stable (short, random); the API accepts `root=<id>`, and the old index for a while longer (old apps).
- `visible`: `"all"`, `"owners"` or a list of device ids.
- No `tags` = a folder as today (no tag), so the existing configuration works without migration apart from assigning
  an `id`.

## Stages

1. **Tags, visibility, folder `id`s, a folder screen in the server settings.** Enforcing visibility everywhere that
   accepts `root`.
2. **Magnet → folder:** `POST /api/library/guess` (name → kind, title, year, season, a folder suggestion with load
   balancing and "the series is already here"), the choice on the phone, `download_location` in Deluge/Transmission.
3. **Background file tasks:** moving (through the client when the torrent is in it), replication, one notification
   at the end.
4. **Disks and tidying up:** usage, duplicates, tidy-up preview.
5. **Shared library and covers** in the app.
6. **Storage profiles:** `tidy` first (lossless, quick), then lossy profiles with a savings preview, verification
   and a trash (decision 16). Builds on stage 3 (background tasks) and the existing converter (`mp_media`).
7. **MCP server** (decision 17): read tools and tags can come early (they need only stage 1), moving and
   re-encoding after stages 3 and 6.

What went into agent 1.6.0 (`mp_tasks.py`, `mp_mcp.py`, `mp_media.cover`, `mp_selfupdate.py`): stages 3, 4 and 6
share one task engine and one preview flow, so a "free up the TOSHIBA disk" plan, a tidy-up by recognition, deleting
duplicates and shrinking are the same kind of task in the app and over MCP. Measured on jmpc (18.11): the library is
only ~59 GB of the 891 GB used on TOSHIBA (the rest isn't in the library), so moving frees at most that; a "free"
plan for 30 GB picked 3 items (28 GB) to My Passport. The tidy-up checked 74 items and proposed 1 move; duplicates:
one pair (2.8 GB).
8. **Browsing the collection as films (agent 1.8.0, `mp_catalog.py`):** besides folders, the library is a catalog of
   titles: genres, age, audio and subtitle languages, cast, directors and topics, without TMDB. Data: the local IMDb
   index (now also rating and runtime), ffprobe of the main video and the subtitle files next to it, and Wikidata by
   IMDb id (cast in billing order with roles, directors, main subjects, FSK/MPAA, the Polish label). The Polish title
   is shown only when the film has Polish audio or subtitles. Covers are synthetic (genre colour + title). The app:
   Discover / Browse / Folders in the Library, a title screen, a person screen, "what next" after a film ends, which
   also lists films that are not on the disks (greyed out), a shared "want to watch" list, and an optional "Search"
   button for a missing film that opens a search address the user typed in the server settings (the project ships
   none). The Downloads screen also lists files sent from phones (`uploads.json`), so they don't stay forgotten in
   "Z telefonu": each one can be moved to the folder identification suggests or kept where it is. The disks screen
   lists every disk, also ones without library folders (the system disk), with "Add folder".
