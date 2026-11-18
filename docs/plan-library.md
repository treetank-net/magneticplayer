# Plan: a tagged library, tidying up disks, covers

Status (2026-11-18): stage 1 (tags, visibility, exclusions) is in agent 1.4.0 and in the app, without a screen during
server setup (for now a pencil next to the folder in the Library). Stage 2 (magnet → folder) is in agent 1.5.0 and in
the app: recognition, downloading straight into the folder with the tag, "Change" with moving and remembering the
choice. Missing: re-recognizing a magnet without a name (`dn=`) once the metadata has been downloaded. The API still
points to a folder by index; `id` is in the configuration for replication. The stages go in order, because each one
builds on the previous one.

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
16. Photos and documents (rules like `year/month/name.pdf`) — outside the app (Immich, PhotoPrism, Paperless-ngx).

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
