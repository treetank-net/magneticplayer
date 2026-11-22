# Plan: one screen per task, task scopes, the library's missing episodes

The owner's design, approved 2026-11-20, is a design canvas: boards A1-E2 plus notes. It is private; the link is in
`CLAUDE.local.md`. This file turns it into work items. Every item names its board, and its acceptance list names
**every element on that board**. An item is done only when each element is on the phone screen, or the owner agreed
to drop it. Last time elements of a prototype were skipped and UX remarks ignored; the rules at the end exist because
of that.

Results and tick marks go to `test-plan.md` ("UX tasks" section), as for every release.

## Decisions (the owner's words, condensed)

1. **One task = one screen = one queue item.** Tasks are never combined behind one screen ("Tidy up + duplicates",
   "Renovation" with options inside). Groups on the list are only headings.
2. Groups: **Tidy up** (folder layout, balance between disks, duplicates, free up space), **Renovation** (fix sound,
   old picture, downscale, compression, for Chromecast, and the advanced Re-encode), **Subtitles** (download, fit),
   **Catalog** (tags).
3. **Downscale is its own task** and appears nowhere else as an option. Targets go down to **480p and 360p** (an
   archive of old series episodes).
4. **Folder layout** sorts by category, kind and age group; **balance** spreads titles between disks.
5. **Re-encode** (B6) is the one deliberate exception: several changes in one pass, for an owner who knows what they
   want. It sits under "Advanced", after the five simple ones, which stay.
6. **Scope.** The task list covers the whole machine by default. Opened from a folder, a category, a series or film, a
   season, a file or a selection, it covers only that group: counters, previews and the task itself.
7. **Tapping a title tile on "On the phone" opens its library page**; that page has every action. The separate quick
   menu screen is gone. ▶ plays at once; ⋮ keeps a few quick actions.
8. "On the phone" keeps its list (agreed); selecting must be obvious ("Select" button, long press).
9. **Search engines are a Settings item** (Settings › Search engines), reached also from a missing title's page.
10. **A series page lists every season and episode from the catalog**, also the ones not on disk, with a comfortable way
    to search for them.
11. **The scoped task list is called "Optimise" / "Optymalizacja"**: it is what we do with these files. Where a label
    fits, the entry point reads "Optimise"; where it doesn't (E1 header, rows, tiles), the same icon (Material
    `AutoFixHigh`, a wand with sparkles; not the gear, which means Settings). The whole-machine screen A1 keeps
    "Disks and tidy-up" because it also shows the disks; "Background work" (C4) keeps its name.
12. **A scope can be a filter**: folder, category and age group, combinable (all must match), besides a title, a
    season, a file or a selection.
13. **Seeding torrents may be moved, but only whole**: the torrent's folder moves through the torrent client as one
    unit and keeps seeding; its files are never split into different folders (layout) or disks (balance).

Out of scope: remote access from outside the home; anything not on a board or in a note. New wishes go to
`test-plan.md` "Noted wishes".

## What exists today (2026-11-20)

| area | where | state |
|---|---|---|
| task list | `LibraryTasks.kt` `DisksScreen` | disks + mixed actions (free, shrink dialog, subtitles dialog, dupes, tidy), running tasks as `TaskCard` |
| plans | `mp_tasks.py` `plan_*`, `POST /api/library/plan` | kinds `free, shrink, modernize, move, tidy, dupes, subs, subs_get, trash` |
| shrink profiles | `mp_tasks.PROFILES` | `tidy` (audio only), `good` 1080p, `compact` 720p, `tiny` 480p; no 360p, no "same resolution" |
| sound fix | inside `plan_shrink` (`audio_fix`) | not a task of its own |
| scope | `plan_shrink(root, path)`, `plan_subs_get(root, path)` | only these two take a folder; nothing takes a list of files or a catalog title |
| pause / queue order | — | missing (cancel only, with confirmation) |
| series episodes | `/api/library/episodes` | files on disk only; the catalog (`mp_catalog.py`, Wikidata) has no episode lists |
| search engines | `cfg.search_engines` on the agent, `SearchEnginesDialog`, opened from server settings (`ServersUi.kt`) | URL placeholders `{title}`, `{imdb}` |
| missing title | `CatalogScreens.kt` `MissingScreen` | reached from Person / Next / Wishlist; from the library search since agent 1.17 (D1, `CatalogSearch.kt`) |
| On the phone | `OfflineTab.kt` | `OfflineCard` tap does nothing unless selecting; `OfflineLine` tap plays |
| phone copies | `Offline.kt` policy `watched / days:2 / days:7 / days:30` | exists; D6 only shows it |

## Shared foundations (first; everything else uses them)

### F1. Scope in the agent

- A scope object accepted by every plan kind and by a new counters endpoint:
  `{"roots": [{"root": 0, "path": "Series/X"}], "files": [{"root": 0, "path": "…"}], "imdb": "tt…", "tag": "anime",
  "age": [0, 7]}` (absent = whole machine). The filter parts (`roots`, `tag`, `age`) combine with AND; `files` and
  `imdb` are exact sets. The label is built from the parts ("Anime · 0+ and 7+").
- `GET /api/library/jobs?scope=<json>` returns, per task kind, `{count, size, saving, eta, applicable, why}`. `why`
  explains "nothing to do", or "whole disks only" for balance, free up and trash. Computed from the background scans;
  no heavy work in the request.
- A task created from a scoped plan stores the scope and a short label; `/api/tasks` returns `scope_label`
  ("Compression · <title>").
- A scope spanning two servers: the app sends one plan per server (each agent knows only its own files).
- `docs/api.md` gets the new parameters and endpoint, with the agent version.

Accept: each plan kind with each scope type returns only items inside the scope (unit test on a temp library); the
counters equal the plan sizes; unscoped calls behave as today.

### F2. Plan kinds split one per task

New or changed kinds, each with its own parameters (no flags that make one kind behave like another):

| task (board) | kind | parameters |
|---|---|---|
| Folder layout (A2) | `layout` | `levels` (ordered: kind, age group, category, genre, film series, decade), age-group mapping |
| Balance (A3) | `balance` | `goal`: `even` / `kind_per_disk` / `fill_newer`; rules: series whole, skip watched-now, seeding torrents whole via the client |
| Duplicates (A4) | `dupes` | `what`: `same_files` / `same_title`; `keep`: `folder` / `quality` / `space` / `manual` |
| Free up (A5) | `free` | `root`, `amount` (50/100/250 GB or "to 15 % free"), `order`: `watched_oldest` / `largest`, `skip_kept` |
| Fix sound (B1) | `audio_fix` | `aac_quality`, `auto_new` (fix new files by itself) |
| Old picture (B2) | `modernize` | `encoder`, `audio`: `keep_if_ok` / `always_aac`; resolution unchanged |
| Downscale (B3) | `downscale` | `target`: 1080/720/480/360, folders, `remember_profile` |
| Compression (B4) | `compress` | `quality`: `transparent` / `balanced` / `saving`, `only_high_bitrate`, `skip_hevc_av1`, `skip_seeding`, folders, `encoder` |
| For Chromecast (B5) | `cast_ready` | `add_aac`, `subs_to_srt`, `avi_to_mkv`; picture never re-encoded |
| Re-encode (B6) | `reencode` | ordered steps (F3) |
| Download subtitles (C1) | `subs_get` | `languages`, `which`: `no_subs` / `no_subs_in_lang` / `new_only`, `order`: `recent` / `alpha`, folders |
| Fit subtitles (C2) | `subs` | `which`: `unchecked` / `watched_now` / `all`, `sub_to_srt`, `mark_bad`, `queue_bad_for_download` |

Old kinds (`shrink`, `tidy`) stay accepted for older apps until the app no longer sends them; the new app sends only
the new ones. A 360p profile and a "same resolution" profile are added to `PROFILES`.

Accept: every row's parameters change the plan (test per parameter); `features` in `/api/info` lists the new kinds
and the app hides a task the agent can't do.

### F3. Re-encode pipeline (agent)

One ffmpeg run per file built from the enabled steps, in a fixed order: sound fix and deinterlace first, then crop,
scale, codec; audio additions; container last. A step the file doesn't need is skipped for that file; the preview says
which steps apply to each file. Warnings for pointless combinations (denoise/crop at 360p). Saved step sets
(`reencode_presets` in the config) with the built-in "Series archive", "For Chromecast", "Old VHS".

### F4. Pause, resume and queue order (agent)

`POST /api/tasks/<id>/pause`, `/resume`, `/move` (`up`). Pause stops at a file boundary or suspends the detached
encode (`mp_detached`); a paused task keeps its place. Needed by C4.

### F5. Episode lists from the catalog (agent) — **decision needed first, see Open questions**

For a recognised series: seasons and episodes (number, title, air date) from a catalog source, cached per series,
refreshed weekly while the series is running. `/api/catalog/title` returns them merged with the files on disk
(`have`, `path`) and marks missing and not-yet-aired ones.

### F6. Job screen template (app)

One composable used by every task screen: back arrow, title, one-sentence description, settings sections (chips,
switches, checkboxes), a summary card fed by F1 counters, a primary "Preview" button that opens the existing
`PlanScreen`. In a scope it shows a "Scope: <label> ✕" bar instead of the folder picker. EN + PL strings for all.

## Screens

Each item lists the elements of its board; the acceptance check is "every listed element is on the screen and works".

### A1. Disks and tidy-up: task list (replaces the actions in `DisksScreen`)

- header "Disks and tidy-up", back arrow;
- scope bar "Scope: whole computer · Narrow…"; Narrow… opens a picker with three combinable filters (folder,
  category, age group) and a title search; applying it opens E2;
- "Background work: N running, M waiting ›" row → C4;
- disk bars (name, bar, "X free of Y");
- headings with a one-line explanation: **Tidy up · move and delete**, **Renovation · changes files, original to
  the trash**, **Subtitles**, **Catalog · doesn't touch files**;
- one row per task: name, one-line description, counter or state on the right in the accent colour ("2 to move",
  "39% / 42% free", "32 groups · 20 GB", "not needed" greyed, "14 files", "5 in 4K", "412 files", "9 files",
  "1113 items", "578", "11 to check" in the warning colour); a "Trash · originals after changes, 14 days · 12 GB" row;
- "ADVANCED" label and the Re-encode row at the end of Renovation;
- every row opens its own screen; none expands in place.

### A2. Folder layout

- description: moves titles into folders by catalog tags wherever they are now;
- "ARRANGE BY (LEVELS IN ORDER)" chips: kind, age group, category (anime…), genre, film series, decade; tapping
  adds or removes a level and the order of taps is the order of levels;
- "IT WILL LOOK LIKE" tree preview updated live from the chosen levels;
- "AGE GROUPS" mapping: 0+ and 7+ → Kids, 12+ → Teens, 16+ and 18+ → Films (editable);
- "DON'T TOUCH": folders I arranged myself (detected from folder tags, the list confirmed before the first run),
  titles without a sure match (count);
- seeding torrents: moved whole through the torrent client; a torrent holding several titles stays together (the
  preview says so);
- note: every title stays on its disk; moving between disks is "Balance";
- primary button "Preview · N to move".

### A3. Balance between disks

- description: moves whole titles between disks; the folder layout on each disk stays the same;
- "GOAL" chips with a hint each: even free space / one kind per disk / fill the newer disk first;
- "NOW → AFTER" per disk with percentages, and "N titles · X GB · about T";
- "RULES": a series stays whole on one disk; don't move what someone watches now; a seeding torrent moves whole
  through the torrent client and keeps seeding; backup folders kept on both disks;
- "Preview".

### A4. Duplicates

- description: removes extra copies; at least one of each group stays;
- "WHAT IS A DUPLICATE" chips with hint: same files / same film in other versions (from the catalog);
- "WHICH COPY TO KEEP": in the fitting folder / better quality / on the disk with space / I'll choose;
- found summary; "Seeding in the torrent client: N. Shown, but deleted in the client.";
- "Checked in the background today at HH:MM · check now";
- "Preview".

### A5. Free up space

- description: deletes titles from a disk (to the trash); doesn't re-encode or move;
- "DISK" chips with free percentage;
- "HOW MUCH" chips 50 / 100 / 250 GB / to 15% free, with the "already X% free: nothing to do" warning;
- "WHAT FIRST": watched by everyone, oldest first / largest files; skip "keep" and running series;
- "INSTEAD OF DELETING" links with amounts to Downscale, Balance and Duplicates (links only), with the note that they
  are separate tasks;
- "Preview".

### B1. Fix sound

- description: tracks with damaged frames become AAC; the picture stays bit for bit;
- "Found N files", background check status ("checked X of Y films; the rest tomorrow"), time and size estimate;
- "FILES": first three with codec and error count, "… and N more";
- "SETTINGS": AAC quality (128k stereo / 256k 5.1), "Fix new files by itself" switch;
- "Originals: trash on the same disk, 14 days.";
- "Preview · N files".

### B2. Modernise old picture

- description: formats phones and TVs can't play (or play badly) become H.264; resolution unchanged;
- "FOUND N FILES" with format and size per file; "Files the phone and Chromecast already play well are skipped.";
- "ENCODER" chips with hint (graphics card fast / processor smaller but slow and loud) and total time;
- "SOUND": unchanged if it works / always AAC;
- "Preview · N files · time".

### B3. Downscale

- description: files bigger than the target are reduced to it; smaller ones are never enlarged;
- "TO" chips 1080p / 720p / 480p / 360p, each with a "how it looks" line;
- "FOLDERS" list with checkboxes, each with its resolution mix and saving at the chosen target;
- "Remember as the profile of these folders" switch with "New files in them will be downscaled too.";
- summary "N files · −X GB · about T h on the graphics card" or "Nothing to do in the chosen folders";
- "Preview".

### B4. Compression

- description: same resolution, smaller file (H.265); downscale is a separate task;
- "QUALITY" chips: no visible difference / balanced / saving, with hint and summary per choice;
- "WHICH FILES": only high bitrate (over 3 GB per hour), skip H.265 and AV1, skip seeding torrents, folders picker;
- "ENCODER": graphics card / processor, daytime;
- summary and "The disk needs up to X GB free while working (originals in the trash).";
- "Preview".

### B5. For Chromecast

- description: today "Prepare" converts at every viewing; this does it once, in the file;
- "FOUND N FILES" with the reason per file (DTS 5.1, AC3 + embedded subtitles, AVI container), "… and N more";
- "WHAT IT DOES": add an AAC stereo track (original kept), embedded subtitles saved next to it as .srt, AVI
  repacked to MKV without re-encoding; "Picture not re-encoded. Files grow about 2-4%. About 2 min per file.";
- "Preview · N files".

### B6. Re-encode (advanced)

- description: several changes in one pass, the file is encoded once;
- "START FROM" preset chips: Series archive / For Chromecast / Old VHS / Empty;
- files scope row (folder picker, or the scope bar);
- six steps, each a switch + name + one-line summary of the chosen option; only one expanded at a time; options:
  resolution (720/480/360), video codec (H.265 saving / H.265 quality / H.264 compatible), old picture (deinterlace /
  denoise / crop black bars), sound (fix damaged / even loudness / leave), Chromecast (add AAC stereo / subtitles to
  .srt), container (MKV / MP4); a step that is off reads "skipped";
- summary card: "N files · 1 pass instead of K · about T · −X GB", the order note, and the warning line for
  pointless combinations;
- "Save set" and "Preview" (per-file list of the steps that apply).

### C1. Download missing subtitles

- description: searches, downloads and fits them to the film's sound; films unchanged;
- "LANGUAGES" chips (multi-select);
- "WHICH FILMS": no subtitles at all / none in this language / only new from today; folders picker;
- "ORDER": recently added and watched / alphabetical;
- summary: "N items · about N min of work", the OpenSubtitles daily limit and days needed, "M films already have
  subtitles in the chosen languages"; for "new only": "New films get subtitles by themselves after they're added";
- "Preview".

### C2. Fit subtitles to the film

- description: compares subtitles already next to the film with its speech and shifts them; downloads nothing;
- "WHICH SUBTITLES": unchecked / only for what's being watched / all again; folders picker;
- "ALONG THE WAY": text .sub to .srt; mark the ones that don't fit (don't delete); add the ones that don't fit to
  "Download missing"; "Original subtitles are set aside.";
- summary and "N subtitle files fitted earlier, skipped";
- "Preview".

### C3. Catalog and tags

- "N of M titles recognised" with the tag kinds (kind, genre, age, animation, anime, film series, year, country and
  language);
- "TO CHECK · N" rows with the reason and "Choose" (existing guess picker), "… and N more";
- "TAGS FROM FOLDERS": folder → tag, and the note that a folder with several titles may add its own tag.

### C4. Background work

- "RUNNING": one card per task: name with its scope/target, state (running / paused), "N of M files · −X GB so far",
  progress bar, "Now: <file> · encoder · about T left", slow-down reason when someone watches from the same disk,
  "Pause/Resume" (F4) and "Cancel…" with the inline confirmation stating what stays, what returns to the original and
  what won't be done;
- "QUEUED": one card per task with its estimate and ↑ (move up) and ✕ (remove);
- "RECENTLY": one line per finished task; errors grouped under it with the fix as a link ("Empty the trash and
  retry").

### D1. Search: have and don't have

- the search field on every Library tab (Discover, Browse, Folders);
- results in sections: "ON YOUR DISKS · N" (title, year, length, folder and disk, "Play"), "YOU DON'T HAVE IT · FROM
  THE FILM DATABASE" (title, year, kind, "want" tag if on the wishlist), "PEOPLE" (role, N films on disks, M others);
- a "don't have" result opens D2.

### D2. A title you don't have

- cover, title, year, length, rating, tags, director;
- "SEARCH" with one button per engine; "Set up search engines ›" → D4; with no engine only that link;
- the phrase used ("Title Year") and the note that a magnet from the browser comes back to the app and lands in the
  title's folder;
- "I want to watch" (wishlist) and "Paste magnet";
- description;
- "SIMILAR · YOU HAVE N" covers marked "on disk" / "you don't have".

### D3. Settings

- sections: SERVERS (each server with state and kind, "+ Add server"), SEARCH ("Search engines ›" with count and
  kinds, "Subtitle languages ›"), ON THE PHONE ("Space for copies" with the delete policy, "Player"), OTHER
  ("Updates", "About");
- the search engines leave the server settings dialog (a link there may stay, pointing here).

### D4. Settings › Search engines

- back goes to where it was opened from (D3 or D2);
- one sentence on what the app does with them, and where they appear (missing title page, subtitles search when
  nothing was found, missing episodes on E1);
- the list: name, kind, address, "Opens: in the app / in the browser";
- "New": name, address with the phrase placeholder, the phrase rules (title + year; episode "Title S02E05"; subtitles
  + language), "In the app" / "In the browser" choice with its explanation, "Check" and "Save".

### D5. On the phone

- header with "Select" / "Done";
- rows: tapping the title opens D6; a separate ▶ plays at once; ⋮ opens quick actions (play from position, download
  subtitles, fit subtitles, keep for good, delete from the phone, details);
- selecting: checkbox on every row, bottom bar "All", "Watched", a subtitle filter, "Delete · N";
- the same in the tile (short) view: tile tap → D6, ▶ on the tile, ⋮ on the tile.

### D6. Title page reached from the phone

- back returns to D5; the page opens scrolled to the tapped episode;
- cover, title, years, seasons, rating, tags, which server and disk;
- "▶ <episode> from <position>" and "On a screen…" (one chooser);
- "ON THIS PHONE" card: copies and size, the delete policy in words, "Download the next 3", "Keep for good",
  "Delete from the phone…";
- season selector with the subtitle summary ("PL 2 · EN 1 · none 1");
- episode rows with a "phone" mark and ⋮: play, download subtitles (server and the copy), fit subtitles, keep for good,
  delete from the phone (the server copy stays), search the engines when there are no subtitles.

### E1. A series in the library: have and don't have (needs F5)

- header: back, breadcrumb, "Select" / "Done";
- cover, title, years, seasons and episodes, rating, tags, "You have N of M · size · disk";
- "▶ <episode> from <position>" and the Optimise icon button (→ E2 with the series as scope; text "Optimise" if the
  width allows);
- one collapsible header per season: "Season N · year", "have X of Y · missing E04" or "you don't have it · N ep.";
- episodes on disk: number, title, state (watched, position, size, subtitles);
- missing episodes: grey, dashed separator, "Search"; not yet aired: the air date instead;
- a missing season: collapsed, "Search the whole season" when expanded, then its episodes;
- the search sheet: phrase (editable, "Title S01E04" or "Title S02"), one button per engine, "Set up ›" → D4,
  "Close"; a magnet from the result lands in the series' folder and the episode appears on the page;
- selecting: checkboxes on episodes that exist, bottom bar "Selected N" and "Optimise N ›" → E2;
- the same for film series (missing parts like missing episodes).

### E2. Optimise: tasks for a scope

- header "Optimise" ("Optymalizacja"), back to where it came from;
- scope bar "Scope: <label>", "<N files · size · missing M>", "✕ whole computer" → A1;
- the same headings and rows as A1, counters from F1 for the scope;
- a task with nothing to do in the scope: greyed, "nothing to do", not hidden; disk-wide tasks (balance, free up,
  trash) replaced by the one-line note that they are on the whole-computer list;
- opening a task keeps the scope (F6 scope bar instead of the folder picker); the created task carries the scope
  label (C4).

### Scope entry points (E2 everywhere)

Label "Optimise" ("Optymalizacja") where it fits, otherwise the `AutoFixHigh` icon with that content description:

- Folders: ⋮ on a folder → "Optimise this folder";
- Library filtered by a category or age group: the icon in the header → the filter becomes the scope;
- film or series page: the icon button; season header ⋮ → "Optimise this season";
- a single file: ⋮ → "Optimise…";
- any selection in the library: "Optimise N" in the selection bar;
- A1: "Narrow…" (folder, category, age group, title).

## Order of work

Each stage is a release of its own (tag after it was seen on the Pixel, in English and Polish).

1. F1 (scope + counters), F2 for the kinds that exist (split `shrink` into `audio_fix`, `modernize`, `downscale`,
   `compress`; `layout` from `tidy`), F6, then A1 and the screens A4, A5, B1-B4, C1, C2. The agent goes first; the
   app shows only tasks in the agent's `features`.
2. New agent work: `balance` (A3), `layout` levels (A2), `cast_ready` (B5), F4 + C4.
3. E2 and the scope entry points.
4. D3 + D4 (engines into Settings), D1 + D2 (search with "don't have"), D5 + D6.
5. F5 + E1 (after the data source decision).
6. F3 + B6.

7. The owner's noted wishes (`test-plan.md` "Noted wishes"), moved here on 2026-11-22 as plan items:
   - **G1. Offline sync points.** The phone keeps positions, watched marks and subtitle choices made without a server
     and sends them when it reaches the server again (the newer change wins, per title). The catalog is fetched
     incrementally: the agent keeps a change counter per library (bumped on every item change); the phone sends the
     last counter it saw and gets only changed and removed items (the whole catalog the first time, after an agent
     restart that lost the counter, or when it is small). Downloaded copies get the server's newer subtitles at the
     same moment.
   - **G2. Episode picker inside the phone player.** A button in the player opens the season's episodes (watched,
     on the phone, current marked); a tap switches episode without leaving the player. The player's title is the
     recognised name ("Series · S2 E3", "Film (1968)"), not the file name.
   - **G3. Audio choice remembered as a kind.** The sheet groups tracks by language with the file's track title
     (lector, dubbing, audio description, commentary), channels and codec. The choice is stored as a kind
     ("original", "Polish lector", "dubbing"): per series folder, otherwise a general preference ("original with
     subtitles", "lector when there is one"). The phone player, mpv and the Chromecast variant use the same choice;
     a Chromecast variant says which audio it has and offers preparing it again for another one.
   - **G4. Suggestions you don't have.** Discover gets a row "Because you watched <title>: not on your disks" from
     the IMDb index (same genres/people, popular, not in the library); a tap opens D2 (search engines, magnet).
   - **G5. Playing on the server's screen from a folder opens the Remote** (the app stayed on the folder).

## Answers (2026-11-20)

1. Episode lists: Wikidata first, IMDb's datasets (`title.episode`, `title.basics`) when Wikidata lacks the series;
   no API keys. Personal, non-commercial use, which a home server is.
2. Search engines stay on the agent (shared by the household); only the screen moves into Settings.
3. Folder layout: the age-group mapping as on the board, editable; "folders I arranged myself" detected from folder
   tags and shown for confirmation before the first run. Follow-up idea: decision 12 (filters as scope).
4. Seeding torrents may be moved whole through the client (decision 13).

## Rules for this plan (why the last prototype lost elements)

- Before building a screen, copy its element list from this file into `test-plan.md` as unticked items.
- After building it, put a phone screenshot next to the board and tick element by element; an element left out
  needs the owner's agreement, written down next to it.
- The owner's UX remarks from testing go to `test-plan.md` "UX notes" the same day, each with an item here or an
  explicit "not doing, because…" the owner saw.
- Wording on the screens follows the boards (Polish) and their English equivalents; no new terms without saying so.
- Numbers on the boards are examples, except the ones the canvas note marks as real.
